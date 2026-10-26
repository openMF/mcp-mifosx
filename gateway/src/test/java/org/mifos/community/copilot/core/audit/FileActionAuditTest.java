/** Copyright since 2026 Mifos Initiative. Mozilla Public License, v. 2.0. */
package org.mifos.community.copilot.core.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mifos.community.copilot.core.approval.ApprovalStore;
import org.mifos.community.copilot.core.auth.CallContext;
import org.mifos.community.copilot.core.llm.LlmToolCall;
import org.mifos.community.copilot.core.audit.ActionAudit.State;
import org.mifos.community.copilot.core.audit.ActionAudit.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class FileActionAuditTest {
    @TempDir Path directory;
    private final CallContext owner = new CallContext("Bearer never-persist-this-secret", "tenant-X", "request-id",
            Map.of("mcpSessionId", "live-session-secret"));

    private String propose(ApprovalStore store) {
        return store.create("conversation", new LlmToolCall("call", "create_client", Map.of("payload", "client PII")),
                "client PII", owner, Map.of("name", "client PII")).cardId();
    }

    @Test void lifecycleIsDurableAndContainsOnlySafeMetadata() throws Exception {
        Path path = directory.resolve("audit.jsonl");
        String id;
        ActionAudit.Entry entry;
        try (var audit = new FileActionAudit(path)) {
            var store = new ApprovalStore(Duration.ofMinutes(5), audit);
            id = propose(store);
            entry = audit.find(id).orElseThrow();
            assertThat(entry.correlationId()).startsWith("card-");
            assertThat(entry.state()).isEqualTo(ActionAudit.State.proposed);
            store.confirm(id, owner, a -> true).orElseThrow();
            store.take(id, owner, a -> true).orElseThrow();
            store.complete(id, owner, "live-session-secret", new ActionAudit.Outcome(true, 201, null));
            assertThat(audit.find(id).orElseThrow()).satisfies(result -> {
                assertThat(result.state()).isEqualTo(ActionAudit.State.succeeded);
                assertThat(result.createdAt()).isEqualTo(entry.createdAt());
                assertThat(result.callerFingerprint()).isEqualTo(owner.fingerprint());
                assertThat(result.tenant()).isEqualTo("tenant-X");
                assertThat(result.httpStatus()).isEqualTo(201);
            });
        }
        try (var reopened = new FileActionAudit(path)) {
            assertThat(reopened.find(id).orElseThrow().state()).isEqualTo(ActionAudit.State.succeeded);
        }
        assertThat(Files.readAllLines(path)).hasSize(4).allSatisfy(line -> {
            assertThat(line).contains(id).doesNotContain("never-persist", "Bearer", "client PII", "live-session-secret",
                    "request-id", "conversation", "payload", "password", "Authorization", "cop-");
        });
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    @Test void expiryIsRecordedByDecisionAndLazySweep() throws Exception {
        try (var audit = new FileActionAudit(directory.resolve("audit.jsonl"))) {
            var store = new ApprovalStore(Duration.ZERO, audit);
            String first = propose(store);
            assertThat(store.confirm(first, owner, a -> true)).isEmpty();
            assertThat(audit.find(first).orElseThrow().state()).isEqualTo(ActionAudit.State.expired);
            String second = propose(store);
            propose(store); // Lazy sweep records expiry before removing the card.
            assertThat(audit.find(second).orElseThrow().state()).isEqualTo(ActionAudit.State.expired);
        }
    }

    @Test void failureAndBlockedCallbackAreDistinctFromAmbiguousExecution() {
        try (var audit = new FileActionAudit(directory.resolve("audit.jsonl"))) {
            var store = new ApprovalStore(Duration.ofMinutes(5), audit);
            String blocked = propose(store);
            store.confirm(blocked, owner, a -> true).orElseThrow();
            store.callbackBlocked(blocked, owner, "live-session-secret");
            assertThat(audit.find(blocked).orElseThrow().failureClass()).isEqualTo("execution_blocked");
            assertThat(store.take(blocked, owner, a -> true)).isEmpty();
            String executing = propose(store);
            store.confirm(executing, owner, a -> true).orElseThrow();
            store.take(executing, owner, a -> true).orElseThrow();
            store.callbackBlocked(executing, owner, "live-session-secret");
            assertThat(audit.find(executing).orElseThrow().state()).isEqualTo(ActionAudit.State.executing);
            store.complete(executing, owner, "live-session-secret", new ActionAudit.Outcome(false, 403, "http_error"));
            assertThat(audit.find(executing).orElseThrow().state()).isEqualTo(ActionAudit.State.failed);
            assertThat(audit.find(executing).orElseThrow().httpStatus()).isEqualTo(403);
        }
    }

    @Test void unavailableOrTornJournalAndCompetingWriterFailClosed() throws Exception {
        Path path = directory.resolve("audit.jsonl");
        try (var first = new FileActionAudit(path); var second = new FileActionAudit(path)) {
            propose(new ApprovalStore(Duration.ofMinutes(5), first));
            assertThatThrownBy(() -> propose(new ApprovalStore(Duration.ofMinutes(5), second)))
                    .hasMessage("Action audit unavailable");
            first.close();
            assertThatThrownBy(() -> propose(new ApprovalStore(Duration.ofMinutes(5), first)))
                    .hasMessage("Action audit unavailable");
        }
        Files.writeString(path, "{\"partial\":");
        try (var torn = new FileActionAudit(path)) {
            assertThatThrownBy(() -> propose(new ApprovalStore(Duration.ofMinutes(5), torn)))
                    .hasMessage("Action audit unavailable");
        }
        try (var missing = new FileActionAudit(directory)) {
            assertThatThrownBy(() -> propose(new ApprovalStore(Duration.ofMinutes(5), missing)))
                    .hasMessage("Action audit unavailable");
        }
    }

    @Test void eachPersistenceGateFailsBeforeChangingApprovalAuthority() {
        try (var durable = new FileActionAudit(directory.resolve("audit.jsonl"))) {
            class FailingAudit implements ActionAudit {
                State failAt = State.proposed;
                public void create(Entry entry) {
                    if (failAt == State.proposed) { throw new IllegalStateException("Audit storage failed"); }
                    durable.create(entry);
                }
                public void transition(String id, State state, Outcome outcome) {
                    if (failAt == state) { throw new IllegalStateException("Audit storage failed"); }
                    durable.transition(id, state, outcome);
                }
                public java.util.Optional<Entry> find(String id) { return durable.find(id); }
            }
            var audit = new FailingAudit();
            var store = new ApprovalStore(Duration.ofMinutes(5), audit);
            assertThatThrownBy(() -> propose(store)).hasMessage("Audit storage failed");
            audit.failAt = State.approved;
            String id = propose(store);
            assertThatThrownBy(() -> store.confirm(id, owner, a -> true)).hasMessage("Audit storage failed");
            assertThat(audit.find(id).orElseThrow().state()).isEqualTo(State.proposed);
            audit.failAt = State.executing;
            String ticket = store.confirm(id, owner, a -> true).orElseThrow();
            assertThatThrownBy(() -> store.take(id, owner, a -> ticket.equals(a.claimToken())))
                    .hasMessage("Audit storage failed");
            assertThat(audit.find(id).orElseThrow().state()).isEqualTo(State.approved);
            audit.failAt = State.succeeded;
            store.take(id, owner, a -> ticket.equals(a.claimToken())).orElseThrow();
            assertThatThrownBy(() -> store.complete(id, owner, "live-session-secret", new Outcome(true, 201, null)))
                    .hasMessage("Audit storage failed");
            assertThat(audit.find(id).orElseThrow().state()).isEqualTo(State.executing);
            assertThat(store.take(id, owner, a -> true)).isEmpty(); // An outcome failure cannot authorize a retry.
        }
    }
}
