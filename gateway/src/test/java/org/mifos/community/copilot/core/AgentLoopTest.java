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
import org.mifos.community.copilot.core.approval.PendingApproval;
import org.mifos.community.copilot.core.auth.CallContext;
import org.mifos.community.copilot.core.contract.StreamEvent;
import org.mifos.community.copilot.core.convo.ConversationStore;
import org.mifos.community.copilot.core.llm.LlmClient;
import org.mifos.community.copilot.core.llm.LlmResult;
import org.mifos.community.copilot.core.llm.LlmToolCall;
import org.mifos.community.copilot.core.tools.Display;
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
import static org.assertj.core.api.Assertions.entry;

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
                description: Approve a loan application. Requires the officer's confirmation.
                summary: "Approve {productName} for {clientName}"
                write: true
                params:
                  - { name: loanId, type: integer, required: true, description: loan, label: Loan account, show: false }
                  - { name: approvedLoanAmount, type: number, required: false, label: Approved amount, format: money }
                  - { name: approvedOnDate, type: string, required: false, label: Approval date, format: date }
                enrich:
                  - path: /api/loans/{loanId}
                    currency: currency.code
                    fields: { Client: clientName, Product: loanProductName }
                rest: { method: POST, path: "/api/{loanId}", body: '{}' }
              - name: mifos_loan_approve
                description: Approve a loan application. Requires the officer's confirmation.
                summary: "Approve {productName} for {clientName}"
                write: true
                params:
                  - { name: loanId, type: integer, required: true, label: Loan account, show: false }
                enrich:
                  - path: /api/loans/{loanId}
                    currency: currency.code
                    fields: { Client: clientName, Product: loanProductName }
                rest: { method: POST, path: "/api/{loanId}?command=approve", body: '{}' }
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
        assertThat(card.data().get("human_summary")).isEqualTo("Approve Weekly Loan for Aisha Bello");
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
        // An invented arg would show on the card but never reach the executed body,
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

    /**
     * An officer is shown a confirmation and never answers it.
     *
     * <p>Approve and reject both write a result against the paused call. Walking away writes
     * nothing, and a conversation carrying a tool call that was asked and never answered is
     * rejected outright by every OpenAI-shaped provider. Their next message would come back
     * HTTP 400, and would keep coming back HTTP 400, so a moment's hesitation would cost them
     * the conversation.
     */
    @Test
    void anUndecidedCardDoesNotBreakTheNextMessage() {
        String conversationId = openConversation();
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 7)))));
        loop().runTurn(conversationId, "approve loan 7", Map.of(), officer, sink);
        assertThat(sink.names()).contains("action_card");

        // No decision. The officer asks for something else instead.
        llm.enqueue(new LlmResult("Here is the list.", List.of()));
        loop().runTurn(conversationId, "actually, show me today's clients", Map.of(), officer, sink);

        assertThat(unansweredIn(llm.lastMessages))
                .as("every tool call the provider is shown has a result against it")
                .isEmpty();
    }

    @Test
    void anAbandonedCardCanNoLongerBeApproved() {
        String conversationId = openConversation();
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 7)))));
        loop().runTurn(conversationId, "approve loan 7", Map.of(), officer, sink);
        String cardId = String.valueOf(sink.byName("action_card").data().get("card_id"));

        llm.enqueue(new LlmResult("Here is the list.", List.of()));
        loop().runTurn(conversationId, "actually, show me today's clients", Map.of(), officer, sink);

        RecordingSink lateDecision = new RecordingSink();
        loop().resume(cardId, true, Map.of(), officer, lateDecision);

        assertThat(executor.executed).as("a card the conversation has moved past does not execute")
                .doesNotContain("write_tool");
        assertThat(lateDecision.byName("error").data().get("code")).isEqualTo("PERMISSION_DENIED");
    }

    /** Start a conversation and return the id the store actually minted for it. */
    private String openConversation() {
        llm.enqueue(new LlmResult("Hello.", List.of()));
        RecordingSink opening = new RecordingSink();
        loop().runTurn(null, "hello", Map.of(), officer, opening);
        return String.valueOf(opening.byName("done").data().get("conversation_id"));
    }

    /** Tool call ids in the trailing assistant tool_calls message with nothing answering them. */
    private static List<String> unansweredIn(List<Map<String, Object>> messages) {
        int last = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).get("tool_calls") != null) {
                last = i;
                break;
            }
        }
        if (last < 0) {
            return List.of();
        }
        java.util.Set<String> answered = new java.util.LinkedHashSet<>();
        for (int i = last + 1; i < messages.size(); i++) {
            Object id = messages.get(i).get("tool_call_id");
            if (id != null) {
                answered.add(String.valueOf(id));
            }
        }
        List<String> unanswered = new ArrayList<>();
        for (Object entry : (List<?>) messages.get(last).get("tool_calls")) {
            Object id = ((Map<?, ?>) entry).get("id");
            String text = id == null ? "call-0" : String.valueOf(id);
            if (!answered.contains(text)) {
                unanswered.add(text);
            }
        }
        return unanswered;
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<LlmResult> queue = new ArrayDeque<>();
        private List<Map<String, Object>> lastMessages = List.of();

        void enqueue(LlmResult result) {
            queue.add(result);
        }

        @Override
        public LlmResult complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                java.util.function.Consumer<String> onToken, java.util.function.BooleanSupplier cancelled) {
            lastMessages = List.copyOf(messages);
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

        /** What the account lookup would return; empty simulates an enrichment that failed. */
        private Map<String, String> enrichment = orderedEnrichment();

        private static Map<String, String> orderedEnrichment() {
            Map<String, String> rows = new java.util.LinkedHashMap<>();
            rows.put(Display.CURRENCY, "USD");
            rows.put("Client", "Aisha Bello");
            rows.put("Product", "Weekly Loan");
            return rows;
        }

        @Override
        public String execute(ToolDefinition tool, Map<String, Object> args, CallContext context,
                String idempotencyKey) {
            executed.add(tool.name());
            lastIdempotencyKey = idempotencyKey;
            return nextResult;
        }

        @Override
        public Map<String, String> enrich(ToolDefinition tool, Map<String, Object> args, CallContext context) {
            return enrichment;
        }

        @Override
        public String businessDate(CallContext context) {
            return "2026-08-21";
        }
    }

    private static final class RecordingSink implements EventSink {
        private final List<StreamEvent> events = new ArrayList<>();

        /** The single event of this name; fails loudly if the turn emitted none or several. */
        StreamEvent only(String name) {
            List<StreamEvent> matches = events.stream().filter((e) -> e.name().equals(name)).toList();
            assertThat(matches).as("events named %s", name).hasSize(1);
            return matches.get(0);
        }

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

    @Test
    @SuppressWarnings("unchecked")
    void theCardNamesTheClientAndProductRatherThanEchoingIds() {
        // Victor's review: an officer confirming money must read "Weekly Loan for Aisha Bello",
        // not "loanId 12". The loop reads the account before it ever shows the card.
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool",
                Map.of("loanId", 12, "approvedLoanAmount", 28000, "approvedOnDate", "today")))));

        loop().runTurn(null, "Approve it at 28000", Map.of(), officer, sink);

        StreamEvent card = sink.only("action_card");
        Map<String, String> rows = (Map<String, String>) card.data().get("rows");
        assertThat(rows).containsExactly(
                entry("Client", "Aisha Bello"),
                entry("Product", "Weekly Loan"),
                entry("Approved amount", "USD 28,000.00"),
                entry("Approval date", "21 August 2026"));
        assertThat(rows).doesNotContainKey("loanId");
        assertThat(card.data().get("human_summary")).isEqualTo("Approve Weekly Loan for Aisha Bello");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRawArgumentsStillTravelWithTheCardAsTheMachineRecord() {
        // The rows are for the human; args stay the exact record of what will execute.
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool",
                Map.of("loanId", 12, "approvedLoanAmount", 28000)))));

        loop().runTurn(null, "Approve it", Map.of(), officer, sink);

        Map<String, Object> args = (Map<String, Object>) sink.only("action_card").data().get("args");
        assertThat(args).containsEntry("loanId", 12).containsEntry("approvedLoanAmount", 28000);
    }

    @Test
    void aFailedLookupFallsBackToTheToolDescriptionRatherThanAVagueTitle() {
        executor.enrichment = Map.of();
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool", Map.of("loanId", 12)))));

        loop().runTurn(null, "Approve it", Map.of(), officer, sink);

        // "Approve {productName} for {clientName}" would otherwise collapse to "Approve".
        assertThat(sink.only("action_card").data().get("human_summary"))
                .isEqualTo("Approve a loan application");
    }

    @Test
    @SuppressWarnings("unchecked")
    void datesAreResolvedAgainstTheBankingCalendarNotTheGatewayClock() {
        // The gateway host can be a day ahead of the tenant; the officer must see the day the
        // write will actually be booked on.
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "write_tool",
                Map.of("loanId", 12, "approvedOnDate", "today")))));

        loop().runTurn(null, "Approve it today", Map.of(), officer, sink);

        Map<String, String> rows = (Map<String, String>) sink.only("action_card").data().get("rows");
        assertThat(rows).containsEntry("Approval date", "21 August 2026");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReceiptAfterAWriteNamesWhatTheOfficerApproved() {
        // The rows read on the card are reused, so the receipt cannot drift from it.
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "mifos_loan_approve", Map.of("loanId", 12)))));
        loop().runTurn(null, "Approve it", Map.of(), officer, sink);
        String cardId = String.valueOf(sink.only("action_card").data().get("card_id"));

        executor.nextResult = "{\"loanId\":12,\"clientId\":7}";
        llm.enqueue(new LlmResult("Done.", List.of()));
        RecordingSink resumeSink = new RecordingSink();
        loop().resume(cardId, true, Map.of(), officer, resumeSink);

        Map<String, Object> card = (Map<String, Object>) resumeSink.only("action_card").data().get("card");
        Map<String, String> data = (Map<String, String>) card.get("data");
        assertThat(data).containsEntry("Client", "Aisha Bello").containsEntry("Product", "Weekly Loan");
        assertThat(data).doesNotContainKey("Loan ID");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReceiptWithNoNamesStillSaysWhichRecordChanged() {
        // Enrichment is best-effort. A receipt showing nothing at all would leave the officer
        // unable to tell which record had just moved, so the identifier appears, labelled.
        executor.enrichment = Map.of();
        llm.enqueue(new LlmResult("", List.of(new LlmToolCall("c1", "mifos_loan_approve", Map.of("loanId", 12)))));
        loop().runTurn(null, "Approve it", Map.of(), officer, sink);
        String cardId = String.valueOf(sink.only("action_card").data().get("card_id"));

        executor.nextResult = "{\"loanId\":12,\"clientId\":7}";
        llm.enqueue(new LlmResult("Done.", List.of()));
        RecordingSink resumeSink = new RecordingSink();
        loop().resume(cardId, true, Map.of(), officer, resumeSink);

        Map<String, Object> card = (Map<String, Object>) resumeSink.only("action_card").data().get("card");
        assertThat((Map<String, String>) card.get("data")).containsEntry("Loan account", "12");
    }

    @Test
    void theRowsOnAPendingApprovalCannotBeChangedAfterTheOfficerHasReadThem() {
        java.util.Map<String, String> mutable = new java.util.LinkedHashMap<>();
        mutable.put("Client", "Aisha Bello");

        PendingApproval approval = new PendingApproval("card-1", "conv-1",
                new LlmToolCall("c1", "write_tool", Map.of()), "Approve", "cop-1",
                officer.fingerprint(), java.time.Instant.now().plusSeconds(60), mutable, Map.of());
        mutable.put("Client", "Someone Else");

        assertThat(approval.rows()).containsExactly(entry("Client", "Aisha Bello"));
    }
}
