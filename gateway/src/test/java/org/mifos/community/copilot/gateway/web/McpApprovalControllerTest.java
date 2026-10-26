/** Copyright since 2026 Mifos Initiative. Mozilla Public License, v. 2.0. */
package org.mifos.community.copilot.gateway.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.mifos.community.copilot.core.audit.ActionAudit;
import org.mifos.community.copilot.core.audit.FileActionAudit;
import java.nio.file.Path;
import java.nio.file.Files;
import org.mifos.community.copilot.core.approval.ApprovalStore;
import org.mifos.community.copilot.core.auth.CallContext;
import org.mifos.community.copilot.core.llm.LlmToolCall;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class McpApprovalControllerTest {
    private static final String TOKEN = "test-bridge-secret-at-least-32-characters";
    private static final String PAYLOAD = """
            {"firstname":"Alice","lastname":"Officer","officeId":1,"active":true,
             "activationDate":"2026-10-23","dateFormat":"yyyy-MM-dd","locale":"en"}
            """;
    @TempDir Path directory;
    private FileActionAudit audit;
    private ApprovalStore store;
    private final CallContext owner = new CallContext("Bearer owner", "tenant", "test");
    private WebTestClient web;

    @BeforeEach void setup() {
        audit = new FileActionAudit(directory.resolve("audit.jsonl"));
        store = new ApprovalStore(Duration.ofMinutes(5), audit);
        web = WebTestClient.bindToController(new McpApprovalController(store, TOKEN, "http://localhost:1")).build();
    }

    @AfterEach void close() { audit.close(); }

    @Test void bridgeRequiresSeparateSecretAndExactIdentityHeaders() {
        for (String token : new String[]{"", "model-provided"}) {
            web.post().uri("/copilot/api/v1/internal/mcp/proposals")
                    .header("Authorization", "Bearer owner").header("Fineract-Platform-TenantId", "tenant")
                    .header("X-MCP-Session-Id", "session").header("X-MCP-Approval-Bridge", token)
                    .bodyValue(Map.of("payload", PAYLOAD)).exchange().expectStatus().isForbidden();
        }
        web.post().uri("/copilot/api/v1/internal/mcp/proposals")
                .header("X-MCP-Approval-Bridge", TOKEN).bodyValue(Map.of("payload", PAYLOAD))
                .exchange().expectStatus().isForbidden();
        web.post().uri("/copilot/api/v1/internal/mcp/proposals")
                .header("Authorization", "Bearer owner", "Bearer stranger")
                .header("Fineract-Platform-TenantId", "tenant").header("X-MCP-Session-Id", "session")
                .header("X-MCP-Approval-Bridge", TOKEN).bodyValue(Map.of("payload", PAYLOAD))
                .exchange().expectStatus().isForbidden();
    }

    @Test void internalClaimCannotAuthorizeItsOwnAction() {
        String id = propose();
        claim(id, "model-ticket").expectStatus().isForbidden();
        assertThat(store.take(id, owner)).isEmpty(); // AgentLoop cannot consume an MCP action.
        assertThat(store.confirm(id, owner, a -> "session".equals(a.session().get("mcpSessionId")))).isPresent();
    }

    @Test void immutablePayloadAndAtomicClaimReuseExistingStore() throws Exception {
        Map<String, Object> mutable = new HashMap<>(Map.of("payload", PAYLOAD));
        var card = store.create("mcp-test", new LlmToolCall("call", "create_client", mutable), "Create Alice",
                new CallContext("Bearer owner", "tenant", "test", Map.of("mcpSessionId", "session")), Map.of());
        mutable.put("payload", PAYLOAD.replace("\"officeId\":1", "\"officeId\":99"));
        assertThatThrownBy(() -> card.toolCall().arguments().put("payload", "tamper"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.confirm(card.cardId(), new CallContext("Bearer stranger", "tenant", "test"), a -> true)).isEmpty();
        assertThat(store.confirm(card.cardId(), new CallContext("Bearer owner", "other", "test"), a -> true)).isEmpty();
        String ticket = store.confirm(card.cardId(), owner, a -> true).orElseThrow();
        assertThat(store.confirm(card.cardId(), owner, a -> true)).isEmpty();
        assertThat(audit.find(card.cardId()).orElseThrow().state()).isEqualTo(ActionAudit.State.approved);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = pool.invokeAll(IntStream.range(0, 24).mapToObj(i -> (java.util.concurrent.Callable<Boolean>) () ->
                    store.take(card.cardId(), owner, a -> ticket.equals(a.claimToken()))
                            .map(a -> { assertThat(a.toolCall().arguments().get("payload")).isEqualTo(PAYLOAD);
                                assertThat(a.expiresAt()).isEqualTo(card.expiresAt());
                                assertThat(a.idempotencyKey()).isEqualTo(card.idempotencyKey()); return true; }).orElse(false)).toList());
            assertThat(results.stream().filter(result -> { try { return result.get(); } catch (Exception e) { throw new AssertionError(e); } }).count()).isEqualTo(1);
        }
        assertThat(audit.find(card.cardId()).orElseThrow().state()).isEqualTo(ActionAudit.State.executing);
        assertThat(Files.readAllLines(directory.resolve("audit.jsonl"))).hasSize(3);
    }

    @Test void snapshotNormalizesNullArgumentsToAnImmutableEmptyMap() {
        var card = store.create("legacy", new LlmToolCall("call", "legacy_write", null), "Legacy action", owner, Map.of());
        assertThat(card.toolCall().id()).isEqualTo("call");
        assertThat(card.toolCall().name()).isEqualTo("legacy_write");
        assertThat(card.toolCall().arguments()).isEmpty();
        assertThatThrownBy(() -> card.toolCall().arguments().put("payload", "tamper"))
                .isInstanceOf(UnsupportedOperationException.class);
        String ticket = store.confirm(card.cardId(), owner, a -> true).orElseThrow();
        assertThat(store.take(card.cardId(), owner, a -> ticket.equals(a.claimToken())))
                .hasValueSatisfying(approval -> assertThat(approval.toolCall().arguments()).isEmpty());
    }

    @Test void snapshotPreservesExistingNullOptionalArguments() {
        Map<String, Object> args = new HashMap<>();
        args.put("optional", null);
        var card = store.create("legacy", new LlmToolCall("call", "legacy_write", args), "Legacy action", owner, Map.of());
        assertThat(card.toolCall().arguments()).containsEntry("optional", null);
        assertThat(store.take(card.cardId(), owner)).isPresent();
    }

    @Test void expiryIsNotExtendedByConfirmation() {
        var expired = new ApprovalStore(Duration.ZERO);
        var card = expired.create("mcp-test", new LlmToolCall("call", "create_client", Map.of("payload", PAYLOAD)),
                "Create Alice", owner, Map.of());
        assertThat(expired.confirm(card.cardId(), owner, a -> true)).isEmpty();
        assertThat(expired.take(card.cardId(), owner, a -> true)).isEmpty();
    }

    @Test void rejectionConsumesAction() {
        String id = propose();
        web.post().uri("/copilot/api/v1/mcp/actions/" + id + "/decision")
                .header("Authorization", "Bearer owner").header("Fineract-Platform-TenantId", "tenant")
                .header("X-MCP-Session-Id", "session").bodyValue(Map.of("decision", "reject"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("cancelled");
        assertThat(store.confirm(id, owner, a -> true)).isEmpty();
        assertThat(audit.find(id).orElseThrow().state()).isEqualTo(ActionAudit.State.rejected);
    }

    @Test void resultRequiresOriginalOwnerTenantSessionAndBridge() {
        String id = propose();
        String ticket = store.confirm(id, owner, a -> true).orElseThrow();
        claim(id, ticket).expectStatus().isOk();
        for (String[] identity : new String[][]{{"Bearer stranger", "tenant", "session"},
                {"Bearer owner", "other", "session"}, {"Bearer owner", "tenant", "other"}}) {
            web.post().uri("/copilot/api/v1/internal/mcp/results/" + id)
                    .header("Authorization", identity[0]).header("Fineract-Platform-TenantId", identity[1])
                    .header("X-MCP-Session-Id", identity[2]).header("X-MCP-Approval-Bridge", TOKEN)
                    .bodyValue(Map.of("success", true, "httpStatus", 201)).exchange().expectStatus().isForbidden();
        }
        var before = audit.find(id).orElseThrow();
        assertThat(before.state()).isEqualTo(ActionAudit.State.executing);
        assertThat(before.tenant()).isEqualTo("tenant");
        assertThat(before.callerFingerprint()).isEqualTo(owner.fingerprint());
        for (int i = 0; i < 2; i++) {
            web.post().uri("/copilot/api/v1/internal/mcp/results/" + id)
                    .header("Authorization", "Bearer owner").header("Fineract-Platform-TenantId", "tenant")
                    .header("X-MCP-Session-Id", "session").header("X-MCP-Approval-Bridge", TOKEN)
                    .bodyValue(Map.of("success", true, "httpStatus", 201)).exchange().expectStatus().isOk();
        }
        assertThat(audit.find(id).orElseThrow().state()).isEqualTo(ActionAudit.State.succeeded);
    }

    @Test void proposalAndClaimFailClosedWhenAuditUnavailable() {
        String id = propose();
        String ticket = store.confirm(id, owner, a -> true).orElseThrow();
        audit.close();
        claim(id, ticket).expectStatus().is5xxServerError();
        assertThatThrownBy(() -> store.take(id, owner, a -> true)).hasMessage("Action audit unavailable");
        web.post().uri("/copilot/api/v1/internal/mcp/proposals")
                .header("Authorization", "Bearer owner").header("Fineract-Platform-TenantId", "tenant")
                .header("X-MCP-Session-Id", "session").header("X-MCP-Approval-Bridge", TOKEN)
                .bodyValue(Map.of("payload", PAYLOAD)).exchange().expectStatus().is5xxServerError();
    }

    private String propose() {
        var response = web.post().uri("/copilot/api/v1/internal/mcp/proposals")
                .header("Authorization", "Bearer owner").header("Fineract-Platform-TenantId", "tenant")
                .header("X-MCP-Session-Id", "session").header("X-MCP-Approval-Bridge", TOKEN)
                .bodyValue(Map.of("payload", PAYLOAD)).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(response.get("status")).isEqualTo("pending_approval");
        return (String) ((Map<?, ?>) response.get("action_card")).get("card_id");
    }

    private WebTestClient.ResponseSpec claim(String id, String ticket) {
        return web.post().uri("/copilot/api/v1/internal/mcp/claims/" + id)
                .header("Authorization", "Bearer owner").header("Fineract-Platform-TenantId", "tenant")
                .header("X-MCP-Session-Id", "session").header("X-MCP-Approval-Bridge", TOKEN)
                .header("X-MCP-Decision-Token", ticket).exchange();
    }
}
