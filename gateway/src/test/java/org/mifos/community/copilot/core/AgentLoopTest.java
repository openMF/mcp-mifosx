/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.community.copilot.core.agent.AgentLoop;
import org.mifos.community.copilot.core.agent.EventSink;
import org.mifos.community.copilot.core.approval.ApprovalStore;
import org.mifos.community.copilot.core.auth.CallContext;
import org.mifos.community.copilot.core.contract.StreamEvent;
import org.mifos.community.copilot.core.convo.ConversationStore;
import org.mifos.community.copilot.core.llm.LlmClient;
import org.mifos.community.copilot.core.llm.LlmResult;
import org.mifos.community.copilot.core.llm.LlmToolCall;
import org.mifos.community.copilot.core.tools.ToolDefinition;
import org.mifos.community.copilot.core.tools.ToolExecutor;
import org.mifos.community.copilot.core.tools.ToolManifest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Banking invariants of the agent loop: write-pause, default-deny, fingerprints, round cap. */
class AgentLoopTest {

    private static final String MANIFEST = """
            tools:
              - name: read_tool
                description: A read tool
                write: false
                params:
                  - { name: id, type: integer, required: true, description: id }
                rest: { method: GET, path: "/api/{id}" }
              - name: write_tool
                description: A write tool
                summary: "Approve loan #{loanId}"
                write: true
                params:
                  - { name: loanId, type: integer, required: true, description: loan }
                rest: { method: POST, path: "/api/{loanId}", body: '{}' }
            """;

    private final CallContext officer = new CallContext("Basic abc", "default", "corr-1");
    private final CallContext otherUser = new CallContext("Basic xyz", "default", "corr-2");

    private ToolManifest manifest;
    private ApprovalStore approvals;
    private ConversationStore conversations;
    private RecordingExecutor executor;
    private ScriptedLlm llm;
    private RecordingSink sink;

    @BeforeEach
    void setUp() {
        manifest = ToolManifest.load(new ByteArrayInputStream(MANIFEST.getBytes(StandardCharsets.UTF_8)));
        approvals = new ApprovalStore(Duration.ofMinutes(5));
        conversations = new ConversationStore();
        executor = new RecordingExecutor();
        llm = new ScriptedLlm();
        sink = new RecordingSink();
    }

    private AgentLoop loop() {
        return new AgentLoop(llm, manifest, executor, approvals, conversations);
    }

    @Test
    void readToolExecutesImmediatelyAndTurnEndsWithDone() {
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "read_tool", Map.of("id", 7)))));
        llm.enqueue(new LlmResult("Here is your data.", List.of()));

        loop().runTurn(null, "show 7", Map.of(), officer, sink);

        assertThat(executor.executed).containsExactly("read_tool");
        assertThat(executor.lastIdempotencyKey).isNull(); // Reads carry no idempotency key.
        assertThat(sink.names()).containsSubsequence("tool_call", "tool_call", "done");
    }

    @Test
    void writeToolPausesWithActionCardAndDoesNotExecute() {
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 42)))));

        loop().runTurn(null, "approve loan 42", Map.of(), officer, sink);

        assertThat(executor.executed).isEmpty(); // NOTHING executed before confirmation.
        assertThat(sink.names()).contains("action_card").doesNotContain("done");
        StreamEvent card = sink.byName("action_card");
        assertThat(card.data().get("human_summary")).isEqualTo("Approve loan #42");
        assertThat(String.valueOf(card.data().get("idempotency_key"))).startsWith("cop-");
    }

    @Test
    void approvalExecutesWithServerMintedIdempotencyKey() {
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 42)))));
        loop().runTurn(null, "approve loan 42", Map.of(), officer, sink);
        String cardId = String.valueOf(sink.byName("action_card").data().get("card_id"));
        String mintedKey = String.valueOf(sink.byName("action_card").data().get("idempotency_key"));

        llm.enqueue(new LlmResult("Done, the loan is approved.", List.of()));
        RecordingSink resumeSink = new RecordingSink();
        loop().resume(cardId, true, Map.of(), officer, resumeSink);

        assertThat(executor.executed).containsExactly("write_tool");
        assertThat(executor.lastIdempotencyKey).isEqualTo(mintedKey);
        assertThat(resumeSink.names()).containsSubsequence("tool_call", "tool_call", "done");
    }

    @Test
    void rejectionExecutesNothingAndInformsTheModel() {
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 42)))));
        loop().runTurn(null, "approve loan 42", Map.of(), officer, sink);
        String cardId = String.valueOf(sink.byName("action_card").data().get("card_id"));

        llm.enqueue(new LlmResult("Understood, cancelled.", List.of()));
        RecordingSink resumeSink = new RecordingSink();
        loop().resume(cardId, false, Map.of(), officer, resumeSink);

        assertThat(executor.executed).isEmpty();
        assertThat(resumeSink.names()).contains("done");
    }

    @Test
    void differentUserCannotDecideSomeoneElsesCard() {
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 42)))));
        loop().runTurn(null, "approve loan 42", Map.of(), officer, sink);
        String cardId = String.valueOf(sink.byName("action_card").data().get("card_id"));

        RecordingSink resumeSink = new RecordingSink();
        loop().resume(cardId, true, Map.of(), otherUser, resumeSink);

        assertThat(executor.executed).isEmpty();
        assertThat(resumeSink.byName("error").data().get("code")).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void doubleApprovalIsImpossible() {
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 42)))));
        loop().runTurn(null, "approve loan 42", Map.of(), officer, sink);
        String cardId = String.valueOf(sink.byName("action_card").data().get("card_id"));

        llm.enqueue(new LlmResult("Done.", List.of()));
        loop().resume(cardId, true, Map.of(), officer, new RecordingSink());
        RecordingSink second = new RecordingSink();
        loop().resume(cardId, true, Map.of(), officer, second);

        assertThat(executor.executed).hasSize(1); // The card was single-use.
        assertThat(second.byName("error").data().get("code")).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void writeCallWithUndeclaredArgumentIsRefusedNotCarded() {
        // An invented arg would show on the card but never reach the executed body —
        // the loop must refuse it so card and execution can never diverge.
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool",
                Map.of("loanId", 42, "expectedDisbursementDate", "2026-09-01")))));
        llm.enqueue(new LlmResult("Let me retry with only the declared arguments.", List.of()));

        loop().runTurn(null, "approve loan 42 and disburse on Sep 1", Map.of(), officer, sink);

        assertThat(executor.executed).isEmpty();
        assertThat(sink.names()).doesNotContain("action_card");
        assertThat(sink.names()).contains("done");
    }

    @Test
    void strangerProbingACardIdDoesNotDestroyIt() {
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 42)))));
        loop().runTurn(null, "approve loan 42", Map.of(), officer, sink);
        String cardId = String.valueOf(sink.byName("action_card").data().get("card_id"));

        // A different identity presenting the card id is denied WITHOUT consuming the card.
        loop().resume(cardId, true, Map.of(), otherUser, new RecordingSink());

        llm.enqueue(new LlmResult("Done.", List.of()));
        RecordingSink ownerSink = new RecordingSink();
        loop().resume(cardId, true, Map.of(), officer, ownerSink);

        assertThat(executor.executed).containsExactly("write_tool"); // Owner's approval still works.
        assertThat(ownerSink.names()).contains("done");
    }

    @Test
    void rejectedWriteSaysNotCompletedNeverExecuted() {
        executor.nextResult = "{\"error\":{\"httpStatus\":400,\"detail\":\"No loan products\"}}";
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 42)))));
        loop().runTurn(null, "approve loan 42", Map.of(), officer, sink);
        String cardId = String.valueOf(sink.byName("action_card").data().get("card_id"));

        llm.enqueue(new LlmResult("The system rejected it.", List.of()));
        RecordingSink resumeSink = new RecordingSink();
        loop().resume(cardId, true, Map.of(), officer, resumeSink);

        String statusLine = resumeSink.events.stream()
                .filter((event) -> event.name().equals("token"))
                .map((event) -> String.valueOf(event.data().get("delta")))
                .filter((delta) -> delta.contains("Executed") || delta.contains("Not completed"))
                .findFirst().orElse("");
        assertThat(statusLine).contains("Not completed").doesNotContain("✔");
    }

    @Test
    void unknownToolIsRefusedByDefaultDeny() {
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "drop_database", Map.of()))));
        llm.enqueue(new LlmResult("That tool does not exist.", List.of()));

        loop().runTurn(null, "do something evil", Map.of(), officer, sink);

        assertThat(executor.executed).isEmpty();
        assertThat(sink.names()).contains("done");
    }

    @Test
    void runawayToolLoopsAreCapped() {
        for (int i = 0; i < 10; i++) {
            llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c" + i, "read_tool", Map.of("id", i)))));
        }

        loop().runTurn(null, "loop forever", Map.of(), officer, sink);

        assertThat(executor.executed).hasSize(6); // MAX_TOOL_ROUNDS
        assertThat(sink.byName("error").data().get("code")).isEqualTo("TOOL_FAILED");
    }

    // ─── Test doubles ──────────────────────────────────────────────────────────

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<LlmResult> queue = new ArrayDeque<>();

        void enqueue(LlmResult result) {
            queue.add(result);
        }

        @Override
        public LlmResult complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                java.util.function.Consumer<String> onToken, java.util.function.BooleanSupplier cancelled) {
            LlmResult result = queue.poll();
            if (result == null) {
                return new LlmResult("(no scripted response)", List.of());
            }
            if (!result.text().isBlank()) {
                onToken.accept(result.text());
            }
            return result;
        }
    }

    private static final class RecordingExecutor implements ToolExecutor {
        private final List<String> executed = new ArrayList<>();
        private String lastIdempotencyKey;
        private String nextResult = "{\"ok\":true}";

        @Override
        public String execute(ToolDefinition tool, Map<String, Object> args, CallContext context,
                String idempotencyKey) {
            executed.add(tool.name());
            lastIdempotencyKey = idempotencyKey;
            return nextResult;
        }
    }

    private static final class RecordingSink implements EventSink {
        private final List<StreamEvent> events = new ArrayList<>();

        @Override
        public void emit(StreamEvent event) {
            events.add(event);
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        List<String> names() {
            return events.stream().map(StreamEvent::name).toList();
        }

        StreamEvent byName(String name) {
            return events.stream().filter((event) -> event.name().equals(name)).findFirst().orElseThrow();
        }
    }
}
