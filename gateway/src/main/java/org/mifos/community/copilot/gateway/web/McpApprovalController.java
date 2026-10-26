/**
 * Copyright since 2026 Mifos Initiative
 * This Source Code Form is subject to the Mozilla Public License, v. 2.0.
 */
package org.mifos.community.copilot.gateway.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mifos.community.copilot.core.approval.ApprovalStore;
import org.mifos.community.copilot.core.approval.PendingApproval;
import org.mifos.community.copilot.core.auth.CallContext;
import org.mifos.community.copilot.core.audit.ActionAudit;
import org.mifos.community.copilot.core.contract.DecisionRequest;
import org.mifos.community.copilot.core.contract.StreamEvent;
import org.mifos.community.copilot.core.llm.LlmToolCall;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Thin transport adapter to the existing ApprovalStore and action-card contract.
 * No LLM tool or internal proposal endpoint can issue a confirmation ticket. */
@RestController
@RequestMapping("/copilot/api/v1")
public class McpApprovalController {
    private final ApprovalStore approvals;
    private final String bridgeToken;
    private final String callerUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public McpApprovalController(ApprovalStore approvals,
            @Value("${MCP_APPROVAL_BRIDGE_TOKEN:}") String bridgeToken,
            @Value("${MCP_CALLER_URL:}") String callerUrl) {
        this.approvals = approvals;
        this.bridgeToken = bridgeToken;
        this.callerUrl = callerUrl.replaceAll("/+$", "");
    }

    public record Proposal(String payload) {}

    @PostMapping("/internal/mcp/proposals")
    public Map<String, Object> propose(@RequestBody Proposal proposal, ServerHttpRequest request) {
        requireBridge(request);
        CallContext context = context(request);
        String session = header(request, "X-MCP-Session-Id");
        JsonNode args = parsePayload(proposal.payload());
        Map<String, String> rows = new LinkedHashMap<>();
        args.fields().forEachRemaining(field -> rows.put(field.getKey(), field.getValue().asText()));
        String summary = "Create client " + args.get("firstname").asText() + " " + args.get("lastname").asText();
        // Payload is an immutable String, held in an immutable map. Never reconstruct
        // it from decision arguments, session facts, defaults or mutable UI state.
        PendingApproval approval = approvals.create("mcp-" + UUID.randomUUID(),
                new LlmToolCall("mcp-" + UUID.randomUUID(), "create_client", Map.of("payload", proposal.payload())),
                summary, new CallContext(context.authorizationHeader(), context.tenantId(), context.correlationId(),
                        Map.of("mcpSessionId", session)), rows);
        Map<String, Object> card = StreamEvent.actionCard(approval.cardId(), "create_client",
                mapper.convertValue(args, new TypeReference<Map<String, Object>>() {}), summary, approval.idempotencyKey(),
                approval.expiresAt().toString(), approval.rows()).data();
        return Map.of("status", "pending_approval", "action_card", card);
    }

    /** Internal atomic claim: requires the secret human-decision ticket as well as
     * the bridge credential, caller, tenant and originating MCP session. */
    @PostMapping("/internal/mcp/claims/{cardId}")
    public Map<String, String> claim(@PathVariable String cardId, ServerHttpRequest request) {
        requireBridge(request);
        CallContext context = context(request);
        String session = header(request, "X-MCP-Session-Id");
        String ticket = header(request, "X-MCP-Decision-Token");
        PendingApproval approval = approvals.take(cardId, context, belongsToSession(session).and(a ->
                a.claimToken() != null && MessageDigest.isEqual(a.claimToken().getBytes(StandardCharsets.UTF_8),
                        ticket.getBytes(StandardCharsets.UTF_8)))).orElseThrow(McpApprovalController::invalid);
        return Map.of("payload", (String) approval.toolCall().arguments().get("payload"),
                "idempotency_key", approval.idempotencyKey(), "card_id", approval.cardId());
    }

    /** Result metadata is accepted only from the trusted bridge and original owner/session.
     * It cannot approve, claim, or retry an action. */
    @PostMapping("/internal/mcp/results/{cardId}")
    public Map<String, String> result(@PathVariable String cardId, @RequestBody ActionAudit.Outcome outcome,
            ServerHttpRequest request) {
        requireBridge(request);
        try {
            approvals.complete(cardId, context(request), header(request, "X-MCP-Session-Id"), outcome);
        } catch (IllegalArgumentException e) { throw invalid(); }
        return Map.of("card_id", cardId, "status", "recorded");
    }

    /** Human-only control plane, with the same decision body and ownership rules
     * as the existing action endpoint. Keep this route outside model/tool access. */
    @PostMapping(value = "/mcp/actions/{cardId}/decision", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> decision(@PathVariable String cardId, @RequestBody DecisionRequest decision,
            ServerHttpRequest request) {
        CallContext context = context(request);
        String session = header(request, "X-MCP-Session-Id");
        if (!"approve".equals(decision.decision()) && !"reject".equals(decision.decision())) { throw invalid(); }
        if (!decision.isApprove()) {
            approvals.reject(cardId, context, belongsToSession(session)).orElseThrow(McpApprovalController::invalid);
            return Mono.just(ResponseEntity.ok("{\"status\":\"cancelled\"}"));
        }
        requireConfigured();
        String ticket = approvals.confirm(cardId, context, belongsToSession(session))
                .orElseThrow(McpApprovalController::invalid);
        return Mono.fromCallable(() -> {
            try {
                HttpRequest callback = HttpRequest.newBuilder(URI.create(callerUrl + "/internal/approved-create-client/" + cardId))
                        .timeout(Duration.ofSeconds(45)).header("Authorization", context.authorizationHeader())
                        .header("X-Mifos-Tenant-Id", context.tenantId()).header("X-MCP-Session-Id", session)
                        .header("X-MCP-Approval-Bridge", bridgeToken).header("X-MCP-Decision-Token", ticket)
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<String> result = http.send(callback, HttpResponse.BodyHandlers.ofString());
                if (result.statusCode() != 200) {
                    approvals.callbackBlocked(cardId, context, session);
                    // Go returns only sanitized errors; keep the human response generic too.
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body("{\"status\":\"failed\",\"card_id\":\"" + cardId + "\"}");
                }
                return ResponseEntity.ok(result.body());
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{\"status\":\"unconfirmed\"}");
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Predicate<PendingApproval> belongsToSession(String session) {
        return a -> "create_client".equals(a.toolCall().name()) && session.equals(a.session().get("mcpSessionId"));
    }

    private CallContext context(ServerHttpRequest request) {
        return new CallContext(header(request, "Authorization"), header(request, "Fineract-Platform-TenantId"), "mcp-" + UUID.randomUUID());
    }

    private String header(ServerHttpRequest request, String name) {
        var values = request.getHeaders().get(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()
                || values.getFirst().contains("\r") || values.getFirst().contains("\n")) { throw invalid(); }
        return values.getFirst();
    }

    private void requireConfigured() {
        try {
            URI uri = URI.create(callerUrl);
            if (bridgeToken.length() < 32 || bridgeToken.contains("\r") || bridgeToken.contains("\n")
                    || uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) { throw invalid(); }
        } catch (IllegalArgumentException e) { throw invalid(); }
    }

    private void requireBridge(ServerHttpRequest request) {
        requireConfigured();
        if (!MessageDigest.isEqual(bridgeToken.getBytes(StandardCharsets.UTF_8),
                header(request, "X-MCP-Approval-Bridge").getBytes(StandardCharsets.UTF_8))) { throw invalid(); }
    }

    private JsonNode parsePayload(String payload) {
        try {
            if (payload == null || payload.length() > 4096) { throw invalid(); }
            JsonNode args = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(payload);
            Set<String> fields = Set.of("firstname", "lastname", "officeId", "active", "activationDate", "dateFormat", "locale");
            if (args == null || !args.isObject() || args.size() != fields.size() || !args.path("officeId").isIntegralNumber()
                    || !args.path("officeId").canConvertToLong() || args.path("officeId").asLong() <= 0
                    || !args.path("active").isBoolean()) { throw invalid(); }
            for (String field : fields) {
                if (!args.has(field)) { throw invalid(); }
                if (!field.equals("officeId") && !field.equals("active")) {
                    JsonNode value = args.get(field);
                    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 200
                            || value.asText().contains("\r") || value.asText().contains("\n") || value.asText().contains("\0")) { throw invalid(); }
                }
            }
            return args;
        } catch (java.io.IOException e) { throw invalid(); }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Confirmation is unavailable or no longer valid");
    }
}
