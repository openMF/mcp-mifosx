/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Streaming client for any OpenAI-compatible chat-completions API: Groq cloud
 * ({@code https://api.groq.com/openai/v1}) and on-prem Ollama ({@code http://host:11434/v1})
 * expose the identical wire shape, which is what makes the provider a pure configuration choice
 * (ADR-001 §2.2).
 *
 * <p>Pure JDK and Jackson, with no framework imports, so the Fineract plugin can embed it unchanged.
 * The API key lives in gateway configuration and is only ever attached to the provider base URL.
 */
public final class OpenAiCompatibleLlmClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public LlmResult complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
            Consumer<String> onToken, BooleanSupplier cancelled) throws LlmException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("temperature", 0.2);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    // Whole-exchange deadline: a hung provider must never pin a worker thread
                    // forever. Generous because it also spans the full streamed response.
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            request = builder.build();
        } catch (IOException e) {
            throw new LlmException("Failed to encode LLM request", e);
        }

        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LlmException("LLM provider unreachable at " + baseUrl, e);
        }
        if (response.statusCode() != 200) {
            response.body().close(); // Release the connection before bailing.
            if (response.statusCode() == 429) {
                throw new LlmException("LLM provider rate limit hit", null, true, false,
                        retryAfterSeconds(response));
            }
            boolean refused = response.statusCode() == 400 || response.statusCode() == 401
                    || response.statusCode() == 403 || response.statusCode() == 404;
            throw new LlmException("LLM provider returned HTTP " + response.statusCode(), null, false, refused);
        }

        return consumeStream(response.body(), onToken, cancelled);
    }

    /** Assemble content deltas and fragmented tool_calls from the SSE line stream. */
    private LlmResult consumeStream(Stream<String> lines, Consumer<String> onToken, BooleanSupplier cancelled)
            throws LlmException {
        StringBuilder text = new StringBuilder();
        // OpenAI streams tool calls as fragments keyed by index: name arrives once,
        // the JSON `arguments` string arrives in pieces that must be concatenated.
        Map<Integer, PartialToolCall> partial = new TreeMap<>();
        try (Stream<String> stream = lines) {
            for (String line : (Iterable<String>) stream::iterator) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode delta = mapper.readTree(payload).path("choices").path(0).path("delta");
                JsonNode content = delta.path("content");
                if (content.isTextual() && !content.asText().isEmpty()) {
                    text.append(content.asText());
                    onToken.accept(content.asText());
                }
                for (JsonNode fragment : delta.path("tool_calls")) {
                    int index = fragment.path("index").asInt(0);
                    PartialToolCall call = partial.computeIfAbsent(index, (i) -> new PartialToolCall());
                    if (fragment.hasNonNull("id")) {
                        call.id = fragment.get("id").asText();
                    }
                    JsonNode function = fragment.path("function");
                    if (function.hasNonNull("name")) {
                        call.name = function.get("name").asText();
                    }
                    if (function.hasNonNull("arguments")) {
                        call.arguments.append(function.get("arguments").asText());
                    }
                }
            }
        } catch (IOException e) {
            throw new LlmException("Failed to read the LLM stream", e);
        }

        List<LlmToolCall> calls = new ArrayList<>();
        for (PartialToolCall call : partial.values()) {
            if (call.name == null) {
                continue;
            }
            calls.add(new LlmToolCall(call.id, call.name, parseArguments(call.arguments.toString())));
        }
        return new LlmResult(text.toString(), calls);
    }

    /** Model-emitted argument JSON may be malformed, so degrade to empty args rather than crashing. */
    private Map<String, Object> parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (IOException e) {
            return Map.of();
        }
    }

    /**
     * The wait the provider asked for, in whole seconds, or zero if it did not ask.
     *
     * <p>Both spellings are in the wild: a plain number of seconds, and the provider-specific
     * one that Groq and others send with a fractional part. Rounded up, because coming back
     * fractionally early only earns a second refusal.
     */
    private static int retryAfterSeconds(HttpResponse<?> response) {
        for (String header : new String[] { "retry-after", "x-ratelimit-reset-tokens",
            "x-ratelimit-reset-requests" }) {
            String value = response.headers().firstValue(header).orElse(null);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                double seconds = value.endsWith("ms")
                        ? Double.parseDouble(value.substring(0, value.length() - 2)) / 1000
                        : Double.parseDouble(value.endsWith("s")
                                ? value.substring(0, value.length() - 1)
                                : value);
                if (seconds > 0) {
                    return (int) Math.ceil(seconds);
                }
            } catch (NumberFormatException e) {
                // A shape we do not know. Better to say nothing than to invent a number.
            }
        }
        return 0;
    }

    private static final class PartialToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
