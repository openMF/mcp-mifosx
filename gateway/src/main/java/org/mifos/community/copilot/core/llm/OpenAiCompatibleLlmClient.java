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
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            Consumer<String> onToken, Consumer<String> onReasoning, BooleanSupplier cancelled)
            throws LlmException {
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

        return consumeStream(response.body(), onToken, onReasoning, cancelled);
    }

    /**
     * The reasoning field names in use across OpenAI-compatible providers, in probe order.
     *
     * <p>Not one name, because there is no agreement on one. DeepSeek and the engines that
     * followed it use {@code reasoning_content}; Ollama, current vLLM and OpenRouter use
     * {@code reasoning}. A given deployment may answer to either depending on its version, so
     * every chunk is checked against all of them rather than the first one that ever worked.
     */
    private static final String[] REASONING_FIELDS = { "reasoning_content", "reasoning", "thinking" };

    /** Assemble content deltas and fragmented tool_calls from the SSE line stream. */
    private LlmResult consumeStream(Stream<String> lines, Consumer<String> onToken, Consumer<String> onReasoning,
            BooleanSupplier cancelled) throws LlmException {
        StringBuilder text = new StringBuilder();
        // Only used by providers that leave <think> markers in the content. Anything that sends
        // reasoning in a field of its own never reaches it.
        ReasoningSplitter inlineThinking = new ReasoningSplitter((answer) -> {
            text.append(answer);
            onToken.accept(answer);
        }, onReasoning);
        boolean reasoningFieldSeen = false;
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

                // Checked by key presence, not by truthiness: Ollama omits the key when there is
                // no reasoning, DeepSeek sends an explicit null, and an empty string is a real
                // value that neither means "absent".
                for (String field : REASONING_FIELDS) {
                    if (delta.hasNonNull(field) && !delta.get(field).asText().isEmpty()) {
                        reasoningFieldSeen = true;
                        onReasoning.accept(delta.get(field).asText());
                    }
                }

                JsonNode content = delta.path("content");
                if (content.isTextual() && !content.asText().isEmpty()) {
                    if (reasoningFieldSeen) {
                        // The provider is separating the two itself, so the content channel is
                        // the answer and nothing else. Running the splitter over it as well
                        // would only risk swallowing a client whose name contains a bracket.
                        text.append(content.asText());
                        onToken.accept(content.asText());
                    } else {
                        inlineThinking.accept(content.asText());
                    }
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
            // Nothing more is coming, so a tail held back as a possible marker is simply text.
            inlineThinking.finish();
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

    /** A duration as providers spell them: {@code 30}, {@code 1500ms}, {@code 2m59.56s}. */
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|h|m|s)?");

    /**
     * Longer than any chat-completions budget legitimately takes to reset.
     *
     * <p>Some providers put an epoch timestamp on these headers rather than a duration. Read as
     * seconds that is a number in the billions, and telling an officer to come back in fifty
     * years is worse than telling them nothing. A day is the line: daily token budgets are the
     * slowest thing that legitimately resets, and an epoch value is five orders of magnitude
     * past it. Anything beyond is treated as a shape we do not understand.
     */
    private static final int LONGEST_CREDIBLE_WAIT_SECONDS = 86_400;

    /**
     * The wait the provider asked for, in whole seconds, or zero if it did not ask.
     *
     * <p>Retry-After is the standard header and the provider's own answer, so it wins outright.
     * Failing that, the two rate-limit budgets reset independently: an officer who has run out
     * of requests is still refused when the token budget resets, so the longer of the two is
     * the honest number rather than whichever header is read first.
     *
     * <p>Rounded up, because coming back fractionally early only earns a second refusal.
     */
    private static int retryAfterSeconds(HttpResponse<?> response) {
        return retryAfterSeconds(response.headers());
    }

    static int retryAfterSeconds(HttpHeaders headers) {
        double stated = headers.firstValue("retry-after")
                .map(OpenAiCompatibleLlmClient::parseRetryAfter)
                .orElse(0d);
        if (stated > 0) {
            return (int) Math.ceil(stated);
        }

        double longest = 0;
        for (String header : new String[] { "x-ratelimit-reset-tokens", "x-ratelimit-reset-requests" }) {
            longest = Math.max(longest, headers.firstValue(header)
                    .map(OpenAiCompatibleLlmClient::parseDuration)
                    .orElse(0d));
        }
        return (int) Math.ceil(longest);
    }

    /** Retry-After carries either a duration or, per RFC 7231, the date it may be retried. */
    static double parseRetryAfter(String value) {
        double duration = parseDuration(value);
        if (duration > 0) {
            return duration;
        }
        try {
            long seconds = Duration.between(Instant.now(),
                    ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds();
            return credible(seconds);
        } catch (DateTimeParseException e) {
            return 0; // A shape we do not know. Better to say nothing than to invent a number.
        }
    }

    /**
     * A duration in seconds, or zero for anything this does not recognise.
     *
     * <p>Compound spellings are the point: Groq sends {@code 2m59.56s} on its reset headers, and
     * reading only the trailing unit would have called that fifty-nine seconds.
     */
    static double parseDuration(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return 0;
        }
        Matcher matcher = DURATION_PART.matcher(text);
        double seconds = 0;
        int consumed = 0;
        while (matcher.find() && matcher.start() == consumed) {
            double amount = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2);
            seconds += amount * switch (unit == null ? "" : unit) {
                case "ms" -> 0.001;
                case "m" -> 60;
                case "h" -> 3600;
                default -> 1;
            };
            consumed = matcher.end();
        }
        // Partly understood is not understood: "5 minutes" must not be read as five seconds.
        return consumed == text.length() ? credible(seconds) : 0;
    }

    private static double credible(double seconds) {
        return seconds > 0 && seconds <= LONGEST_CREDIBLE_WAIT_SECONDS ? seconds : 0;
    }

    private static final class PartialToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
