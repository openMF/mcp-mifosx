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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

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
import java.util.regex.Pattern;

/**
 * Direct-REST tool executor: maps manifest tools onto the Fineract REST API using the
 * OFFICER'S OWN forwarded credential, since the gateway holds no Fineract account (ADR-001 §2.1).
 *
 * <p>This is the transport hedge that works against any Fineract today; the MCP executor
 * targeting the Fineract plugin's {@code /mcp} endpoint slots in behind the same interface.
 * Pure JDK and Jackson, with no framework imports.
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

    /** The office each officer actually belongs to, keyed by fingerprint. */
    private final java.util.Map<String, java.util.Optional<String>> homeOfficeByOfficer =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Offices each officer may work in, keyed by their fingerprint so one cannot answer for another.
     *
     * <p>Only successful answers go in here. Caching a failure would mean one bad moment from
     * {@code /offices} disabled the check for the rest of the process's life, which is a long
     * time to be unable to tell whose branch is whose.
     */
    private final java.util.Map<String, java.util.Set<String>> officesByOfficer =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        // Normalised first, so what executes is exactly what the card showed. The office is
        // worked out from the officer's own credential before anything else is resolved.
        Map<String, Object> effective = withComputed(tool,
                withDefaults(tool, withOffice(tool, normalizeArguments(tool, args, context), context), context));
        String path = substitutePath(rest.path(), effective, today);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(fineractBaseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", context.authorizationHeader())
                .header("Fineract-Platform-TenantId", context.tenantId())
                .header("X-Correlation-Id", context.correlationId());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // Fineract's CommandSource dedups on this natively, so approved writes are exactly-once.
            builder.header("Idempotency-Key", idempotencyKey);
        }

        if ("GET".equalsIgnoreCase(rest.method())) {
            builder.GET();
        } else {
            String body = buildBody(rest.bodyTemplate(), effective, today, context.session());
            builder.method(rest.method().toUpperCase(Locale.ROOT),
                    HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            builder.header("Content-Type", "application/json");
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpConnectTimeoutException e) {
            // Never got a connection, so nothing was sent and nothing can have run. This is a
            // definite failure, and saying "we are not sure" would be its own kind of wrong.
            throw new ToolExecutionException("Could not reach Fineract for " + tool.name(), 0, e);
        } catch (java.net.http.HttpTimeoutException e) {
            // Sent, and the answer never came. For a write that is not a refusal, and it must
            // not be reported as one.
            throw new ToolExecutionException("Timed out waiting for " + tool.name(), 0, e, tool.write());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ToolExecutionException("Fineract unreachable for " + tool.name(), 0, e);
        }

        if (response.statusCode() >= 500) {
            // The banking system did not answer, it fell over. Calling that a rejection sends
            // an officer looking for what they did wrong, when the truthful answer is that
            // there is nothing wrong with the request and nothing they can do about it. A
            // write is left indeterminate, because a 502 from a proxy says nothing about
            // whether the server behind it committed.
            throw new ToolExecutionException(
                    "Fineract is not responding (HTTP " + response.statusCode() + ")",
                    response.statusCode(), null, tool.write());
        }

        if (response.statusCode() == 401 || isPermissionDenial(response)) {
            // Auth outcomes need special loop handling (session expiry / RBAC denial).
            throw new ToolExecutionException(
                    "Fineract returned HTTP " + response.statusCode() + " for " + tool.name(),
                    response.statusCode(), null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Application errors go back to the model as STRUCTURED JSON (never a quoted
            // string carrying escaped JSON) so both the LLM and the UI read them cleanly.
            return applicationError(tool.name(), response.statusCode(), response.body(),
                    tool.redactFields(), effective);
        }
        return truncate(redact(response.body(), tool.redactFields(), effective), MAX_RESULT_CHARS);
    }

    /**
     * The officer's arguments, filled out from the record that owns the unspecified ones.
     *
     * <p>Fineract rejects a loan application that omits its interest rate, repayment
     * frequency, amortisation or strategy, so those fields have to be sent. They belong to the
     * loan product, and reading them from it is the difference between a loan on the terms the
     * institution configured and a loan on whatever was written into this file.
     *
     * <p>An argument the officer supplied always wins. A lookup that fails changes nothing,
     * and the request goes on to fail at Fineract with a message naming the missing field,
     * which is a better answer than a silent wrong rate.
     */
    Map<String, Object> withDefaults(ToolDefinition tool, Map<String, Object> args, CallContext context) { // package-private for tests
        ToolDefinition.Defaults spec = tool.defaults();
        java.util.Map<String, Object> effective = new java.util.LinkedHashMap<>();
        if (args != null) {
            effective.putAll(args);
        }
        if (spec == null || spec.path() == null || spec.fields().isEmpty()) {
            return effective;
        }
        boolean anythingMissing = spec.fields().keySet().stream()
                .anyMatch((name) -> effective.get(name) == null || String.valueOf(effective.get(name)).isBlank());
        if (!anythingMissing) {
            return effective; // The officer named everything; no need to ask.
        }
        try {
            String path = substitutePath(spec.path(), effective, businessDate(context));
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(fineractBaseUrl + path))
                            .timeout(Duration.ofSeconds(15))
                            .header("Accept", "application/json")
                            .header("Authorization", context.authorizationHeader())
                            .header("Fineract-Platform-TenantId", context.tenantId())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return effective;
            }
            JsonNode body = mapper.readTree(response.body());
            for (Map.Entry<String, String> field : spec.fields().entrySet()) {
                Object supplied = effective.get(field.getKey());
                if (supplied != null && !String.valueOf(supplied).isBlank()) {
                    continue;
                }
                JsonNode node = at(body, field.getValue());
                if (node == null || node.isMissingNode() || node.isNull()) {
                    continue;
                }
                effective.put(field.getKey(), node.isNumber() ? (Object) node.numberValue() : node.asText());
            }
        } catch (ToolExecutionException | IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return effective;
    }

    /**
     * The office a write lands in, worked out from the officer's own credential.
     *
     * <p>Fineract requires an office on a new client and offers no way to ask it who the
     * caller is, so the gateway asks which offices this credential can reach. Where that is
     * exactly one, it is theirs and there is nothing to decide. Where it is several, the
     * officer says which, and it shows on the confirmation card so they see the branch before
     * agreeing to it.
     *
     * <p>Deliberately not taken from the request the browser sends. A value the server can
     * establish for itself should not be accepted on trust from a client, and routing it
     * through the language model would let a branch be chosen by a sentence.
     */
    private Map<String, Object> withOffice(ToolDefinition tool, Map<String, Object> args, CallContext context)
            throws ToolExecutionException {
        String body = tool.rest() == null ? null : tool.rest().bodyTemplate();
        if (!tool.write() || body == null || !body.contains("${officeId}")) {
            return args;
        }
        String key = context.fingerprint() + "|" + context.tenantId();
        java.util.Set<String> reachable = officesByOfficer.get(key);
        if (reachable == null) {
            // Absent means the question could not be asked, which is not the same as an answer
            // of none. Only a real answer is remembered, so a bad minute does not become
            // permanent by being cached.
            reachable = readOffices(context).orElseThrow(() -> new ToolExecutionException(
                    "Could not establish which offices you work in, so this was not carried out."
                            + " Please try again.",
                    0, null));
            officesByOfficer.put(key, reachable);
        }
        Object stated = args.get("officeId");
        if (stated != null && !String.valueOf(stated).isBlank()) {
            // Checked against what the credential actually reaches, always. Accepting it
            // whenever the list happened to be empty was a way in: one failed lookup and any
            // office the model named went to the wire unexamined.
            if (!reachable.contains(String.valueOf(stated))) {
                throw new ToolExecutionException("That office is not one you can work in.", 403, null);
            }
            return args;
        }
        if (reachable.size() == 1) {
            // Nothing in doubt, so nothing to ask. Signing in first would make every creation
            // in a single-branch institution wait on a call whose answer cannot change this.
            return withOfficeId(args, reachable.iterator().next());
        }
        // More than one branch in reach, so the officer's own is the only sensible default.
        // An administrator sees every office there is, and asking which one they are sitting
        // in is a strange question with no good answer.
        java.util.Optional<String> home = homeOffice(key, context);
        if (home.isPresent() && reachable.contains(home.get())) {
            return withOfficeId(args, home.get());
        }
        throw new ToolExecutionException(
                reachable.isEmpty()
                        ? "Your login does not reach any office, so this was not carried out."
                                + " Please tell your administrator."
                        : "You work in more than one office, so please say which one this is for.",
                0, null);
    }

    private static Map<String, Object> withOfficeId(Map<String, Object> args, String officeId) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(args);
        out.put("officeId", Long.parseLong(officeId));
        return out;
    }

    /**
     * The officer's own office, remembered only once it is known.
     *
     * <p>Caching the failure would mean one bad moment from the sign-in left that officer
     * being asked to name their branch for the life of the process. The same mistake was
     * already made once with the reachable set; it is not worth making twice.
     */
    private java.util.Optional<String> homeOffice(String key, CallContext context) {
        java.util.Optional<String> known = homeOfficeByOfficer.get(key);
        if (known != null) {
            return known;
        }
        java.util.Optional<String> found = readHomeOffice(context);
        if (found.isPresent()) {
            homeOfficeByOfficer.put(key, found);
        }
        return found;
    }

    /**
     * The office this officer belongs to, as Fineract itself reports it.
     *
     * <p>Fineract answers a sign-in with the user's own office, which is the only place that
     * knows it. Reaching it needs the password, and the password is already in the header this
     * gateway forwards to Fineract on every call, so nothing new is being trusted or held. It
     * is never logged and never leaves this method.
     *
     * <p>Empty when the credential is not Basic, or the sign-in did not answer. The caller
     * falls back to the reachable set, and refuses rather than guessing if that is ambiguous.
     */
    private java.util.Optional<String> readHomeOffice(CallContext context) {
        String header = context.authorizationHeader();
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return java.util.Optional.empty();
        }
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int split = decoded.indexOf(':');
            if (split < 0) {
                return java.util.Optional.empty();
            }
            ObjectNode credentials = mapper.createObjectNode()
                    .put("username", decoded.substring(0, split))
                    .put("password", decoded.substring(split + 1));
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(fineractBaseUrl
                                    + "/fineract-provider/api/v1/authentication"))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .header("Fineract-Platform-TenantId", context.tenantId())
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(credentials)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return java.util.Optional.empty();
            }
            JsonNode body = mapper.readTree(response.body());
            return body.hasNonNull("officeId")
                    ? java.util.Optional.of(body.get("officeId").asText())
                    : java.util.Optional.empty();
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return java.util.Optional.empty(); // Fall back to the reachable set.
        }
    }

    /**
     * Office ids this credential can see.
     *
     * <p>An empty {@code Optional} means the question could not be asked, and an empty set
     * means it was asked and the answer was none. Collapsing those two into one empty set is
     * what let a failed lookup read as a permissive answer.
     */
    private java.util.Optional<java.util.Set<String>> readOffices(CallContext context) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(fineractBaseUrl + "/fineract-provider/api/v1/offices"))
                            .timeout(Duration.ofSeconds(15))
                            .header("Accept", "application/json")
                            .header("Authorization", context.authorizationHeader())
                            .header("Fineract-Platform-TenantId", context.tenantId())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return java.util.Optional.empty();
            }
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            for (JsonNode office : mapper.readTree(response.body())) {
                if (office.hasNonNull("id")) {
                    ids.add(office.get("id").asText());
                }
            }
            return java.util.Optional.of(ids);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return java.util.Optional.empty();
        }
    }

    /**
     * Values derived from two others, once the product's own numbers are known.
     *
     * <p>Only a product of two parameters is supported, which is all that is needed and all
     * that belongs in a manifest. A loan's term is its repayment count times how often it
     * repays; writing the term as the repayment count is correct only when a loan repays
     * every period, and silently halves the term of a fortnightly product.
     *
     * <p>Anything the officer stated is left as they stated it.
     */
    private Map<String, Object> withComputed(ToolDefinition tool, Map<String, Object> args) {
        if (tool.computed().isEmpty()) {
            return args;
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(args);
        for (Map.Entry<String, String> rule : tool.computed().entrySet()) {
            Object already = out.get(rule.getKey());
            if (already != null && !String.valueOf(already).isBlank()) {
                continue;
            }
            String[] operands = rule.getValue().split("\\*");
            if (operands.length != 2) {
                continue;
            }
            Double left = asNumber(out.get(operands[0].trim()));
            Double right = asNumber(out.get(operands[1].trim()));
            if (left == null || right == null) {
                continue; // Not enough to compute with; Fineract will name what is missing.
            }
            double product = left * right;
            out.put(rule.getKey(), product == Math.rint(product) ? (Object) (long) product : (Object) product);
        }
        return out;
    }

    private Double asNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
     * Fields whose optional argument was not supplied are REMOVED from the body, because Fineract
     * must never receive an empty-string stand-in for a field the officer did not set.
     */
    String buildBody(String template, Map<String, Object> args, String today) { // package-private for tests
        return buildBody(template, args, today, Map.of());
    }

    /**
     * Fill the request body.
     *
     * <p>The template is itself valid JSON, with every slot written as a quoted
     * {@code "${name}"}, so it is parsed and walked rather than edited as text. Replacing
     * tokens by repeated string substitution meant a value could be read back as a token on
     * a later pass: a client whose surname was literally {@code ${mobileNo}} was persisted
     * with their phone number as their surname.
     *
     * <p>A slot with nothing to fill it is dropped, because Fineract must not receive an empty
     * string standing in for a field the officer did not set.
     */
    String buildBody(String template, Map<String, Object> args, String today, Map<String, Object> session) {
        if (template == null) {
            return "{}";
        }
        try {
            JsonNode parsed = mapper.readTree(template);
            if (!parsed.isObject()) {
                return template;
            }
            ObjectNode filled = mapper.createObjectNode();
            fill((ObjectNode) parsed, filled, args == null ? Map.of() : args,
                    session == null ? Map.of() : session);
            return mapper.writeValueAsString(filled);
        } catch (IOException e) {
            // A template that is not JSON is a manifest bug, not a runtime condition.
            throw new IllegalStateException("Tool body template is not valid JSON", e);
        }
    }

    /** Copy the template into {@code out}, resolving each slot and dropping the unfilled ones. */
    private void fill(ObjectNode template, ObjectNode out, Map<String, Object> args, Map<String, Object> session) {
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode node = field.getValue();
            if (node.isObject()) {
                ObjectNode nested = mapper.createObjectNode();
                fill((ObjectNode) node, nested, args, session);
                out.set(field.getKey(), nested);
                continue;
            }
            String slot = node.isTextual() ? slotName(node.asText()) : null;
            if (slot == null) {
                out.set(field.getKey(), node); // A literal the manifest meant to send.
                continue;
            }
            Object value = slot.startsWith(SESSION_PREFIX)
                    ? session.get(slot.substring(SESSION_PREFIX.length()))
                    : args.get(slot);
            if (value == null || String.valueOf(value).isBlank()) {
                // Including a session slot nothing filled. No manifest declares one now that the
                // office is worked out from the credential, and an unfilled slot is a field the
                // officer did not set, not a reason to throw out of a request that is being built.
                continue; // Nothing to send, so send no field at all.
            }
            if (value instanceof Integer || value instanceof Long) {
                out.put(field.getKey(), ((Number) value).longValue());
            } else if (value instanceof Number) {
                out.put(field.getKey(), ((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                out.put(field.getKey(), (Boolean) value);
            } else {
                out.put(field.getKey(), String.valueOf(value));
            }
        }
    }

    private static final String SESSION_PREFIX = "session.";
    private static final Pattern SLOT = Pattern.compile("^\\$\\{([A-Za-z0-9_.]+)}$");

    /** The name inside a {@code ${...}} slot, or null when the text is an ordinary value. */
    private static String slotName(String text) {
        java.util.regex.Matcher matcher = SLOT.matcher(text);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Models often say "today" for dates; resolve it to Fineract's expected format, using the
     * CORE BANKING business date rather than the gateway host clock. A date after the business
     * date is clamped to it: Fineract rejects future-dated commands, and the officer meant "now".
     */
    private String normalizeValue(String name, Object value, String today) {
        return normalizeValue(name, value, today, false);
    }

    /**
     * A date as Fineract wants to read it.
     *
     * <p>Dates after the business date are pulled back to it, because Fineract refuses to book
     * an approval or a repayment in its own future and the officer would otherwise get a
     * validation error with no explanation. A parameter the manifest marks
     * {@code futureAllowed} is left alone: an expected disbursement is supposed to be ahead,
     * and moving it to today silently rewrites every instalment date on the schedule Fineract
     * derives from it.
     */
    String normalizeValue(String name, Object value, String today, boolean futureAllowed) { // package-private for tests
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
        boolean clamp = !futureAllowed && parsed.isAfter(businessDate);
        return (clamp ? businessDate : parsed).format(FINERACT_DATE);
    }

    /**
     * The arguments as they will actually be sent.
     *
     * <p>Called before the confirmation card is built, so the officer reads the value that
     * will run. Working this out twice, once for the card and once for the request, is how a
     * card came to say "1 September" while the wire carried today's date.
     */
    @Override
    public Map<String, Object> normalizeArguments(ToolDefinition tool, Map<String, Object> args,
            CallContext context) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        String today = businessDate(context);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean || value == null) {
                out.put(entry.getKey(), value);
                continue;
            }
            out.put(entry.getKey(), normalizeValue(entry.getKey(), value, today, futureAllowed(tool, entry.getKey())));
        }
        return out;
    }

    private boolean futureAllowed(ToolDefinition tool, String param) {
        if (tool == null || tool.params() == null) {
            return false;
        }
        return tool.params().stream()
                .filter((p) -> p.name().equals(param))
                .findFirst()
                .map(ToolDefinition.Param::allowsFuture)
                .orElse(false);
    }


    /**
     * Fetch the human context for a pending write: the account number, the product and the
     * client, so the officer confirms against names rather than identifiers. Read-only, and
     * performed with the officer's own credential like every other call.
     */
    @Override
    public java.util.Map<String, String> enrich(ToolDefinition tool, Map<String, Object> args, CallContext context) {
        if (tool.enrich() == null || tool.enrich().isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
        for (ToolDefinition.Enrich spec : tool.enrich()) {
            readInto(rows, spec, args, context);
        }
        return rows;
    }

    /**
     * One enrichment read, merged into {@code rows}. Presentation only: a lookup that fails
     * leaves the card thinner but must never fail the officer's turn.
     */
    private void readInto(java.util.Map<String, String> rows, ToolDefinition.Enrich spec, Map<String, Object> args,
            CallContext context) {
        if (spec.path() == null || spec.fields().isEmpty()) {
            return;
        }
        try {
            String path = substitutePath(spec.path(), args, businessDate(context));
            HttpRequest request = HttpRequest.newBuilder(URI.create(fineractBaseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Authorization", context.authorizationHeader())
                    .header("Fineract-Platform-TenantId", context.tenantId())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return;
            }
            JsonNode body = mapper.readTree(response.body());
            String currency = spec.currencyPath() != null ? text(at(body, spec.currencyPath())) : currencySymbol(body);
            if (!currency.isBlank()) {
                rows.putIfAbsent(Display.CURRENCY, currency);
            }
            for (Map.Entry<String, String> field : spec.fields().entrySet()) {
                String pointer = field.getValue();
                boolean money = pointer.startsWith("#money:");
                JsonNode node = at(body, money ? pointer.substring("#money:".length()) : pointer);
                if (node == null || node.isMissingNode() || node.isNull()) {
                    continue;
                }
                rows.put(field.getKey(), money ? Display.money(node.asDouble(), currency) : node.asText());
            }
        } catch (ToolExecutionException | IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText();
    }

    /**
     * Whether a 403 is the officer being refused, rather than the operation being refused.
     *
     * <p>Fineract answers 403 for two unrelated things: a role that lacks the permission, and
     * a domain rule the request breaks, such as approving a loan after the date it is meant to
     * be disbursed. Telling an officer their role is wrong when the real problem is a date
     * sends them to an administrator instead of to the field they need to change, so only the
     * first is treated as a permission failure. A rule violation arrives with the reason in
     * {@code errors[]}, and is reported like any other rejection, with that reason attached.
     */
    private boolean isPermissionDenial(HttpResponse<String> response) {
        if (response.statusCode() != 403) {
            return false;
        }
        try {
            JsonNode errors = mapper.readTree(response.body()).path("errors");
            // No explanation to pass on, so there is nothing more useful to say than "denied".
            return !errors.isArray() || errors.isEmpty();
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /** Walk a dotted path such as {@code summary.principalOutstanding}. */
    private JsonNode at(JsonNode root, String dotted) {
        JsonNode node = root;
        for (String part : dotted.split(Pattern.quote("."))) {
            if (node == null) {
                return null;
            }
            node = node.path(part);
        }
        return node;
    }

    /** The account's own currency, so an amount is shown in the money it is actually in. */
    private String currencySymbol(JsonNode body) {
        JsonNode currency = body.path("currency");
        for (String key : new String[] { "displaySymbol", "code" }) {
            JsonNode value = currency.path(key);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
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

    /**
     * The tenant's configured business date, or null if the module is not enabled or the
     * response cannot be read as one.
     *
     * <p>Returns null rather than throwing on a body we cannot parse. A proxy that answers
     * 200 with an HTML error page would otherwise take the whole lookup down to the host
     * clock, when the response's own Date header is still a better answer.
     */
    private String configuredBusinessDate(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        try {
            JsonNode date = mapper.readTree(response.body()).path("date");
            if (date.isArray() && date.size() == 3) { // Fineract returns [yyyy, M, d].
                return LocalDate.of(date.get(0).asInt(), date.get(1).asInt(), date.get(2).asInt()).toString();
            }
            return date.isTextual() && parseIsoOrNull(date.asText()) != null ? date.asText() : null;
        } catch (IOException | RuntimeException e) {
            return null; // Unreadable body, or [2026, 13, 45]; let the Date header answer.
        }
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
     * Error bodies get the SAME redaction + truncation as success bodies, because Fineract error
     * payloads can echo request PII, and they flow to the LLM like any tool result.
     */
    private String applicationError(String toolName, int status, String body,
            java.util.List<String> redactFields, Map<String, Object> args) {
        String safeBody = truncate(redact(body == null ? "" : body, redactFields, args), 2_000);
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
    /**
     * Mask the fields a tool says are private, by name and by value.
     *
     * <p>By name is not enough. Masking a key called {@code mobileNo} catches the read that
     * returns a client, and misses the thing that actually leaks: Fineract rejects a duplicate
     * with "Client with mobileNo `0712345678` already exists", where the number sits in prose
     * under {@code defaultUserMessage} and again under {@code errors[].value}. No key is named
     * after the parameter anywhere in that payload, so nothing was masked, and the whole
     * rejection went into the conversation and on to the model.
     *
     * <p>So the submitted values are masked too, wherever they appear in the text. Short ones
     * are left alone: replacing every "1" in a document to protect an id of 1 would destroy
     * the message and protect nothing worth protecting.
     */
    // Package-private so the redaction tests can feed it a real Fineract error payload.
    String redact(String json, java.util.List<String> redactFields, Map<String, Object> args) {
        if (redactFields == null || redactFields.isEmpty()) {
            return json;
        }
        java.util.List<String> secrets = secrets(redactFields, args);
        try {
            // Decoded first. Masking the raw document compares against escaped text, so a
            // value carrying a quote, a backslash or an accent the server wrote as \\uXXXX
            // never matched itself and stayed in the prose while looking masked.
            JsonNode root = mapper.readTree(json);
            redactNode(root, redactFields, secrets);
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            // Not JSON, so the text is all there is and masking it directly is the best available.
            String out = json;
            for (String secret : secrets) {
                out = maskWithin(out, secret);
            }
            return out;
        }
    }

    /** The submitted values behind the fields a tool calls private, trimmed and non-empty. */
    private static java.util.List<String> secrets(java.util.List<String> redactFields, Map<String, Object> args) {
        java.util.List<String> secrets = new java.util.ArrayList<>();
        if (args == null) {
            return secrets;
        }
        for (String field : redactFields) {
            Object value = args.get(field);
            String text = value == null ? null : String.valueOf(value).trim();
            if (text != null && !text.isEmpty()) {
                secrets.add(text);
            }
        }
        return secrets;
    }

    /**
     * Replace one value wherever it appears in a piece of decoded text.
     *
     * <p>A short value is matched whole, so masking an external id of {@code A12} does not eat
     * the same three characters inside a longer word. Skipping short ones outright, which is
     * what this did first, left the shortest ids as the only ones that leaked.
     */
    private static String maskWithin(String text, String secret) {
        if (secret.length() >= MIN_REDACTABLE_LENGTH) {
            return text.replace(secret, MASK);
        }
        // Pattern.quote makes the value a literal, and the lookarounds are single characters,
        // so there is nothing here for a crafted value to make backtrack.
        return text.replaceAll("(?<![\\p{L}\\p{N}])" + Pattern.quote(secret) + "(?![\\p{L}\\p{N}])", MASK);
    }

    private static String maskAll(String text, java.util.List<String> secrets) {
        String out = text;
        for (String secret : secrets) {
            out = maskWithin(out, secret);
        }
        return out;
    }

    /** Above this length a value is distinctive enough to mask wherever it appears. */
    private static final int MIN_REDACTABLE_LENGTH = 4;

    private static final String MASK = "•••";

    private void redactNode(JsonNode node, java.util.List<String> fields, java.util.List<String> secrets) {
        if (node instanceof ObjectNode object) {
            for (String field : fields) {
                if (object.has(field)) {
                    object.put(field, MASK);
                }
            }
            // Fineract names the offending parameter and carries its value alongside, rather
            // than under a key of that name, so the pair has to be read together.
            if (fields.contains(object.path("parameterName").asText())) {
                if (object.has("value")) {
                    object.put("value", MASK);
                }
                if (object.get("args") instanceof ArrayNode arguments) {
                    arguments.forEach((argument) -> {
                        if (argument instanceof ObjectNode entry && entry.has("value")) {
                            entry.put("value", MASK);
                        }
                    });
                }
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child != null && child.isTextual()) {
                    // The message prose, where Fineract writes the value it is complaining about.
                    object.put(name, maskAll(child.asText(), secrets));
                } else {
                    redactNode(child, fields, secrets);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual()) {
                    array.set(i, TextNode.valueOf(maskAll(child.asText(), secrets)));
                } else {
                    redactNode(child, fields, secrets);
                }
            }
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + " …(truncated)" : value;
    }
}
