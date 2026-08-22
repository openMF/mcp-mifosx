/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.mifos.community.copilot.core.auth.CallContext;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Direct-REST tool executor: maps manifest tools onto the Fineract REST API using the
 * OFFICER'S OWN forwarded credential — the gateway holds no Fineract account (ADR-001 §2.1).
 *
 * <p>This is the transport hedge that works against any Fineract today; the MCP executor
 * targeting the Fineract plugin's {@code /mcp} endpoint slots in behind the same interface.
 * Pure JDK + Jackson — no framework imports.
 */
public final class FineractRestToolExecutor implements ToolExecutor {

    /** Fineract's long-form command date format, e.g. "09 August 2026". */
    private static final DateTimeFormatter FINERACT_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
    /** Tool output fed back to the model is capped so huge Fineract payloads cannot blow the context. */
    private static final int MAX_RESULT_CHARS = 8_000;
    /** The business date rarely moves; re-reading it once every few minutes is plenty. */
    private static final long BUSINESS_DATE_TTL_MS = 5 * 60_000L;

    /**
     * Fineract is multi-tenant and each tenant carries its own business date, so a single
     * shared entry would serve one tenant's calendar to another.
     */
    private final java.util.Map<String, CachedDate> businessDateByTenant = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedDate(String value, long expiresAt) {}

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String fineractBaseUrl;

    public FineractRestToolExecutor(String fineractBaseUrl) {
        this.fineractBaseUrl = fineractBaseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                // Generous: sandbox/gateway-fronted Fineracts can be slow to accept connections,
                // and JVMs on dual-stack hosts may burn seconds on IPv6 before falling back.
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL) // Fineract behind an API gateway may 30x.
                .build();
    }

    @Override
    public String execute(ToolDefinition tool, Map<String, Object> args, CallContext context, String idempotencyKey)
            throws ToolExecutionException {
        ToolDefinition.RestMapping rest = tool.rest();
        if (rest == null || rest.path() == null) {
            throw new ToolExecutionException("Tool has no REST mapping: " + tool.name(), 0, null);
        }

        String today = businessDate(context);
        String path = substitutePath(rest.path(), args, today);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(fineractBaseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", context.authorizationHeader())
                .header("Fineract-Platform-TenantId", context.tenantId())
                .header("X-Correlation-Id", context.correlationId());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // Fineract's CommandSource dedups on this natively — approved writes are exactly-once.
            builder.header("Idempotency-Key", idempotencyKey);
        }

        if ("GET".equalsIgnoreCase(rest.method())) {
            builder.GET();
        } else {
            String body = buildBody(rest.bodyTemplate(), args, today);
            builder.method(rest.method().toUpperCase(Locale.ROOT),
                    HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            builder.header("Content-Type", "application/json");
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ToolExecutionException("Fineract unreachable for " + tool.name(), 0, e);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            // Auth outcomes need special loop handling (session expiry / RBAC denial).
            throw new ToolExecutionException(
                    "Fineract returned HTTP " + response.statusCode() + " for " + tool.name(),
                    response.statusCode(), null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Application errors go back to the model as STRUCTURED JSON (never a quoted
            // string carrying escaped JSON) so both the LLM and the UI read them cleanly.
            return applicationError(tool.name(), response.statusCode(), response.body(), tool.redactFields());
        }
        return truncate(redact(response.body(), tool.redactFields()), MAX_RESULT_CHARS);
    }

    /** Replace {param} tokens in the path, URL-encoding values; unresolved required tokens fail fast. */
    private String substitutePath(String template, Map<String, Object> args, String today) throws ToolExecutionException {
        String out = template;
        if (args != null) {
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                out = out.replace("{" + entry.getKey() + "}", URLEncoder
                        .encode(normalizeValue(entry.getKey(), entry.getValue(), today), StandardCharsets.UTF_8));
            }
        }
        if (out.contains("{")) {
            throw new ToolExecutionException("Missing required argument for path " + template, 0, null);
        }
        return out;
    }

    /**
     * Fill the JSON body template. Tokens are always written as quoted "${param}"; string
     * values are JSON-escaped in place, numbers/booleans replace the quoted token unquoted.
     * Fields whose optional argument was not supplied are REMOVED from the body — Fineract
     * must never receive an empty-string stand-in for a field the officer did not set.
     */
    String buildBody(String template, Map<String, Object> args, String today) { // package-private for tests
        if (template == null) {
            return "{}";
        }
        String out = template;
        if (args != null) {
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                String quotedToken = "\"${" + entry.getKey() + "}\"";
                Object value = entry.getValue();
                if (value == null || String.valueOf(value).isBlank()) {
                    continue; // Treat blank args as omitted; the field is stripped below.
                }
                if (value instanceof Number || value instanceof Boolean) {
                    out = out.replace(quotedToken, String.valueOf(value));
                } else {
                    out = out.replace(quotedToken, quoteJson(normalizeValue(entry.getKey(), value, today)));
                }
                // Deliberately NO unquoted "${key}" replacement: values must never be able to
                // rewrite JSON structure or trigger recursive token substitution.
            }
        }
        // Strip whole fields whose optional token was never filled: '"field":"${tok}",' / ',"field":"${tok}"'.
        out = out.replaceAll("\"[A-Za-z0-9_]+\"\\s*:\\s*\"\\$\\{[A-Za-z0-9_]+}\"\\s*,", "");
        out = out.replaceAll(",\\s*\"[A-Za-z0-9_]+\"\\s*:\\s*\"\\$\\{[A-Za-z0-9_]+}\"", "");
        out = out.replaceAll("\"[A-Za-z0-9_]+\"\\s*:\\s*\"\\$\\{[A-Za-z0-9_]+}\"", "");
        return out;
    }

    /**
     * Models often say "today" for dates; resolve it to Fineract's expected format, using the
     * CORE BANKING business date rather than the gateway host clock. A date after the business
     * date is clamped to it: Fineract rejects future-dated commands, and the officer meant "now".
     */
    private String normalizeValue(String name, Object value, String today) {
        String raw = String.valueOf(value);
        boolean isDateParam = name.toLowerCase(Locale.ROOT).contains("date");
        if (!isDateParam) {
            return raw;
        }
        LocalDate businessDate = parseIsoOrNull(today) != null ? LocalDate.parse(today) : LocalDate.now();
        if ("today".equalsIgnoreCase(raw) || raw.isBlank() || "null".equalsIgnoreCase(raw)) {
            return businessDate.format(FINERACT_DATE);
        }
        LocalDate parsed = parseIsoOrNull(raw);
        if (parsed == null) {
            return raw; // Already a Fineract-formatted or unrecognized value; pass it through.
        }
        return (parsed.isAfter(businessDate) ? businessDate : parsed).format(FINERACT_DATE);
    }

    /** RFC 1123 HTTP date ("Fri, 21 Aug 2026 19:27:50 GMT") to a yyyy-MM-dd calendar day. */
    private String parseHttpDateOrNull(String header) {
        try {
            return java.time.ZonedDateTime
                    .parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toLocalDate()
                    .toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LocalDate parseIsoOrNull(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Fineract's configurable business date, cached briefly. Falls back to the host clock when
     * the endpoint is unavailable (older deployments or the module being disabled).
     */
    @Override
    public String businessDate(CallContext context) {
        String tenant = context.tenantId() == null ? "default" : context.tenantId();
        long now = System.currentTimeMillis();
        CachedDate cached = businessDateByTenant.get(tenant);
        if (cached != null && now < cached.expiresAt()) {
            return cached.value();
        }
        String resolved = fetchBusinessDate(context);
        businessDateByTenant.put(tenant, new CachedDate(resolved, now + BUSINESS_DATE_TTL_MS));
        return resolved;
    }

    /**
     * Ask the core banking system what day it is, falling back to this host's clock.
     * A date that is wrong by a day is still better than a turn that fails outright.
     */
    private String fetchBusinessDate(CallContext context) {
        try {
            HttpRequest request = HttpRequest
                    .newBuilder(URI.create(fineractBaseUrl + "/fineract-provider/api/v1/businessdate/BUSINESS_DATE"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Authorization", context.authorizationHeader())
                    .header("Fineract-Platform-TenantId", context.tenantId())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String configured = configuredBusinessDate(response);
            return configured != null ? configured : serverCalendarDay(response);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return LocalDate.now().toString();
        }
    }

    /** The tenant's configured business date, or null if the module is not enabled. */
    private String configuredBusinessDate(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        JsonNode date = mapper.readTree(response.body()).path("date");
        if (date.isArray() && date.size() == 3) { // Fineract returns [yyyy, M, d].
            return LocalDate.of(date.get(0).asInt(), date.get(1).asInt(), date.get(2).asInt()).toString();
        }
        return date.isTextual() && parseIsoOrNull(date.asText()) != null ? date.asText() : null;
    }

    /**
     * Many deployments run without the business-date module and the endpoint 404s. The
     * response's own Date header still carries the core banking server's calendar day, which
     * beats this host's clock: a gateway in UTC+5:30 has already rolled over to tomorrow while
     * Fineract is still on today, and Fineract rejects future-dated commands.
     */
    private String serverCalendarDay(HttpResponse<String> response) {
        return response.headers()
                .firstValue("date")
                .map(this::parseHttpDateOrNull)
                .filter((day) -> day != null)
                .orElseGet(() -> LocalDate.now().toString());
    }

    /**
     * {"error":{"tool":...,"httpStatus":404,"detail":<parsed body or raw text>}}
     * Error bodies get the SAME redaction + truncation as success bodies — Fineract error
     * payloads can echo request PII, and they flow to the LLM like any tool result.
     */
    private String applicationError(String toolName, int status, String body, java.util.List<String> redactFields) {
        String safeBody = truncate(redact(body == null ? "" : body, redactFields), 2_000);
        ObjectNode error = mapper.createObjectNode();
        error.put("tool", toolName);
        error.put("httpStatus", status);
        try {
            error.set("detail", mapper.readTree(safeBody));
        } catch (IOException e) {
            error.put("detail", safeBody);
        }
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("error", error);
        return wrapper.toString();
    }

    private String quoteJson(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            return "\"\"";
        }
    }

    /** Mask configured PII fields before the payload can reach a cloud LLM (ADR-001 §2.2). */
    private String redact(String json, java.util.List<String> redactFields) {
        if (redactFields == null || redactFields.isEmpty()) {
            return json;
        }
        try {
            JsonNode root = mapper.readTree(json);
            redactNode(root, redactFields);
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            return json; // Not JSON — nothing to redact structurally.
        }
    }

    private void redactNode(JsonNode node, java.util.List<String> fields) {
        if (node instanceof ObjectNode object) {
            for (String field : fields) {
                if (object.has(field)) {
                    object.put(field, "•••");
                }
            }
            object.forEach((child) -> redactNode(child, fields));
        } else if (node.isArray()) {
            node.forEach((child) -> redactNode(child, fields));
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + " …(truncated)" : value;
    }
}
