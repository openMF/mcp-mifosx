/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.agent;

import org.mifos.community.copilot.core.approval.ApprovalStore;
import org.mifos.community.copilot.core.approval.PendingApproval;
import org.mifos.community.copilot.core.auth.CallContext;
import org.mifos.community.copilot.core.contract.ErrorCode;
import org.mifos.community.copilot.core.contract.StreamEvent;
import org.mifos.community.copilot.core.convo.ConversationStore;
import org.mifos.community.copilot.core.llm.LlmClient;
import org.mifos.community.copilot.core.llm.LlmException;
import org.mifos.community.copilot.core.llm.LlmResult;
import org.mifos.community.copilot.core.llm.LlmToolCall;
import org.mifos.community.copilot.core.tools.Display;
import org.mifos.community.copilot.core.tools.ArgumentCheck;
import org.mifos.community.copilot.core.tools.ToolDefinition;
import org.mifos.community.copilot.core.tools.ToolExecutionException;
import org.mifos.community.copilot.core.tools.ToolExecutor;
import org.mifos.community.copilot.core.tools.ToolManifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Copilot brain: LLM turn -> tool calls -> feed results back, until the model answers in
 * prose. Framework-free by design so the mentor's Fineract plugin can host it unchanged.
 *
 * <p>Banking invariants enforced HERE, not in the model (ADR-001 §04):
 * <ul>
 *   <li>default-deny, so a tool absent from the manifest is refused, whatever the model says;</li>
 *   <li>every WRITE pauses: the loop stores a single-use approval and streams an action_card,
 *       ending the turn WITHOUT a done event; execution happens only via {@link #resume};</li>
 *   <li>the approval card is built from the PARSED function call, never from model prose;</li>
 *   <li>at most {@link #MAX_TOOL_ROUNDS} tool rounds per turn, so no runaway loops;</li>
 *   <li>tool results feed the model, but rule custody stays in the system prompt.</li>
 * </ul>
 */
public final class AgentLoop {

    private static final int MAX_TOOL_ROUNDS = 6;

    private final LlmClient llm;
    private final ToolManifest manifest;
    private final ToolExecutor executor;
    private final ApprovalStore approvals;
    private final ConversationStore conversations;

    public AgentLoop(LlmClient llm, ToolManifest manifest, ToolExecutor executor, ApprovalStore approvals,
            ConversationStore conversations) {
        this.llm = llm;
        this.manifest = manifest;
        this.executor = executor;
        this.approvals = approvals;
        this.conversations = conversations;
    }

    /** Run one chat turn. Emits contract events into {@code sink} until done/paused/error. */
    public void runTurn(String requestedConversationId, String userMessage, Map<String, Object> screenContext,
            CallContext context, EventSink sink) {
        String fingerprint = context.fingerprint();
        String conversationId = conversations.resolve(fingerprint, requestedConversationId);
        settleAbandonedCalls(conversationId, context, fingerprint);
        conversations.append(fingerprint, conversationId, Map.of("role", "user", "content", userMessage));
        drive(conversationId, screenContext, context, sink);
    }

    /**
     * Close off a confirmation the officer never answered.
     *
     * <p>Pausing for a card leaves an assistant {@code tool_calls} message in the history with
     * no result against it, because the result is what the decision produces. Approve and
     * reject both supply one. Walking away supplies nothing, and every OpenAI-shaped provider
     * rejects a conversation where a tool call was asked and never answered. Without this the
     * officer's next message returns HTTP 400 and that conversation never works again, which
     * is a strange way to punish somebody for changing their mind.
     *
     * <p>So the abandoned call gets an honest result saying it was never carried out, and the
     * card is dropped so it cannot be approved after the conversation has moved on.
     */
    private void settleAbandonedCalls(String conversationId, CallContext context, String fingerprint) {
        approvals.discardFor(conversationId, context);
        for (String callId : unansweredToolCalls(fingerprint, conversationId)) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "tool");
            message.put("tool_call_id", callId);
            message.put("content", "{\"status\":\"not_executed\",\"detail\":\"The officer did not confirm"
                    + " this action, so nothing was carried out. Ask again if it is still wanted.\"}");
            conversations.append(fingerprint, conversationId, message);
        }
    }

    /**
     * Ids from the trailing assistant {@code tool_calls} message that nothing has answered.
     *
     * <p>Only the trailing one matters: any earlier batch was settled when the turn that
     * raised it finished, and a turn cannot pause twice.
     */
    private List<String> unansweredToolCalls(String fingerprint, String conversationId) {
        List<Map<String, Object>> messages = conversations.messages(fingerprint, conversationId);
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
        List<String> unanswered = new java.util.ArrayList<>();
        Object raw = messages.get(last).get("tool_calls");
        if (raw instanceof List<?> calls) {
            for (Object entry : calls) {
                if (entry instanceof Map<?, ?> call) {
                    Object id = call.get("id");
                    String text = id == null ? "call-0" : String.valueOf(id);
                    if (!answered.contains(text)) {
                        unanswered.add(text);
                    }
                }
            }
        }
        return unanswered;
    }

    /** Resume a paused turn after the officer's decision. */
    public void resume(String cardId, boolean approved, Map<String, Object> screenContext, CallContext context,
            EventSink sink) {
        Optional<PendingApproval> taken = approvals.take(cardId, context);
        if (taken.isEmpty()) {
            // Unknown, expired, already decided, or a different identity: all read the same.
            sink.emit(StreamEvent.error(ErrorCode.PERMISSION_DENIED,
                    "This confirmation is no longer valid. Please ask again.", false));
            sink.emit(StreamEvent.done(""));
            return;
        }
        PendingApproval approval = taken.get();
        String fingerprint = context.fingerprint();
        String conversationId = approval.conversationId();
        LlmToolCall call = approval.toolCall();

        if (!approved) {
            conversations.append(fingerprint, conversationId, toolResultMessage(call,
                    "{\"status\":\"cancelled\",\"detail\":\"The officer rejected this action. Nothing was executed.\"}"));
            drive(conversationId, screenContext, context, sink);
            return;
        }

        ToolDefinition tool = manifest.find(call.name()).orElse(null);
        if (tool == null) {
            sink.emit(StreamEvent.error(ErrorCode.TOOL_FAILED, "Tool is no longer available.", false));
            sink.emit(StreamEvent.done(conversationId));
            return;
        }
        // The server-minted key from card creation goes to Fineract as Idempotency-Key:
        // a retry of this approval can never execute twice.
        // Execute under the session captured on the card, so a value the officer's screen
        // owned when they read it is the value that runs when they confirm.
        CallContext executionContext = new CallContext(context.authorizationHeader(), context.tenantId(),
                context.correlationId(), approval.session());
        ExecStatus status = executeAndRecord(tool, call, executionContext, approval.idempotencyKey(), conversationId,
                fingerprint, sink, approval.rows());
        if (status == ExecStatus.AUTH_FAILED) {
            // Auth expired mid-decision: put the card back (same id, same idempotency key) so
            // the officer's retry after re-login succeeds instead of dead-ending.
            approvals.restore(approval);
            return;
        }
        if (status == ExecStatus.APP_ERROR && nothingWasSent(conversationId, fingerprint)) {
            // Rejected before the request left, so the officer can fix the problem and decide
            // again on the same card rather than starting over.
            approvals.restore(approval);
        }
        if (status == ExecStatus.UNKNOWN) {
            // Sent, no answer. Saying "not completed" would be a guess, and the wrong guess
            // costs a second disbursement. The card goes back with its original idempotency
            // key, so a retry that turns out to be a duplicate is refused by Fineract itself.
            approvals.restore(approval);
            sink.emit(StreamEvent.token("? Not confirmed: " + approval.humanSummary()
                    + ". The banking system did not answer in time, so this may or may not have gone"
                    + " through. Open the account and check before trying again.\n\n"));
            sink.emit(StreamEvent.done(conversationId));
            return;
        }
        // Status line BEFORE the summary turn: if the LLM is unavailable or rate-limited for
        // the summary, the officer must still see what happened. It must never say "Executed"
        // for an action the core banking system rejected, which misleads on a money path.
        if (status == ExecStatus.OK) {
            sink.emit(StreamEvent.token("✔ Executed: " + approval.humanSummary() + "\n\n"));
        } else {
            sink.emit(StreamEvent.token("✖ Not completed: " + approval.humanSummary()
                    + ". The banking system rejected it. Details follow.\n\n"));
        }
        if (sink.isCancelled()) {
            return;
        }
        drive(conversationId, screenContext, context, sink);
    }

    /** Outcome of one tool execution, as far as the officer is concerned. */
    private enum ExecStatus {
        /** Fineract accepted the operation. */
        OK,
        /** Fineract rejected it (validation/business error), recorded for the model to explain. */
        APP_ERROR,
        /** The officer's session expired, so the turn already ended with AUTH_EXPIRED. */
        AUTH_FAILED,
        /** The request was sent and no answer came back, so nobody knows whether it ran. */
        UNKNOWN
    }

    /** The shared LLM<->tools cycle; ends with done, an action_card pause, or an error. */
    private void drive(String conversationId, Map<String, Object> screenContext, CallContext context, EventSink sink) {
        String fingerprint = context.fingerprint();
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmResult result;
            try {
                result = llm.complete(
                        withSystemPrompt(screenContext, conversations.messages(fingerprint, conversationId), context),
                        manifest.openAiSchemas(),
                        (delta) -> sink.emit(StreamEvent.token(delta)),
                        sink::isCancelled);
            } catch (LlmException e) {
                // A refusal is not an outage. Telling an officer to try again shortly, when the
                // key is wrong or the request was malformed, sends them round a loop that cannot
                // come out anywhere. Say it is not going to work and let somebody look at it.
                // Being throttled and being down are different things and were told the same
                // way, so a self-inflicted twenty second wait read as the service being broken.
                sink.emit(llmErrorEvent(e));
                sink.emit(StreamEvent.done(conversationId));
                return;
            } catch (RuntimeException e) {
                // The response body is read as a stream of lines, and a connection that dies
                // mid-read surfaces as an unchecked wrapper rather than the declared failure.
                // Letting it escape leaves the officer watching a stream that never ends.
                sink.emit(StreamEvent.error(ErrorCode.LLM_UNAVAILABLE,
                        "The AI model stopped responding. Please try again.", true));
                sink.emit(StreamEvent.done(conversationId));
                return;
            }
            if (sink.isCancelled()) {
                return; // Stop is silent: nothing else may run after a cancel.
            }

            if (!result.text().isBlank()) {
                conversations.append(fingerprint, conversationId,
                        Map.of("role", "assistant", "content", result.text()));
            }
            if (!result.wantsTools()) {
                sink.emit(StreamEvent.done(conversationId));
                return;
            }

            conversations.append(fingerprint, conversationId, assistantToolCallMessage(result.toolCalls()));
            List<LlmToolCall> batch = result.toolCalls();
            for (int i = 0; i < batch.size(); i++) {
                LlmToolCall call = batch.get(i);
                ToolDefinition tool = manifest.find(call.name()).orElse(null);
                if (tool == null) {
                    // Default-deny: the model asked for something outside the manifest.
                    conversations.append(fingerprint, conversationId, toolResultMessage(call,
                            "{\"error\":\"Tool '" + call.name() + "' is not available.\"}"));
                    continue;
                }
                // Values the web app would not accept are not values the Copilot may send.
                // Checked before the card, because the card is where the officer agrees to
                // this, and before enrichment, which costs a call that a bad value wastes.
                List<String> invalid = ArgumentCheck.problems(tool, call.arguments());
                if (!invalid.isEmpty()) {
                    conversations.append(fingerprint, conversationId, toolResultMessage(call,
                            "{\"error\":\"not_valid\",\"problems\":" + jsonArray(invalid)
                                    + ",\"detail\":\"Nothing was sent. Tell the officer what is wrong,"
                                    + " in your own words, and ask for a value that works.\"}"));
                    continue;
                }
                if (tool.write()) {
                    // Card/execution fidelity: an arg the model invented beyond the declared
                    // params would show on the card yet never reach the request body. Refuse
                    // the call so the model retries with only declared arguments, because what the
                    // officer approves must be exactly what executes.
                    List<String> undeclared = undeclaredArguments(tool, call);
                    if (!undeclared.isEmpty()) {
                        conversations.append(fingerprint, conversationId, toolResultMessage(call,
                                "{\"error\":\"Unknown argument(s) " + undeclared
                                        + " for this tool. Retry using only the declared parameters.\"}"));
                        continue;
                    }
                    // Read the account, product and client first, so the officer confirms
                    // against names rather than identifiers.
                    // Normalise before anything is shown. A date the executor would rewrite
                    // has to be rewritten here, or the officer approves one value and another
                    // one executes.
                    LlmToolCall normalized = new LlmToolCall(call.id(), call.name(),
                            executor.normalizeArguments(tool, call.arguments(), context));
                    Map<String, String> enriched = executor.enrich(tool, normalized.arguments(), context);
                    Map<String, String> rows = cardRows(tool, normalized, enriched, context);
                    PendingApproval approval = approvals.create(conversationId, normalized,
                            summaryFor(tool, normalized, enriched), context, rows);
                    // OpenAI-style history requires a tool result for EVERY id in the assistant's
                    // tool_calls message. The paused call gets its result on resume; any siblings
                    // after it are marked not-executed NOW so the next LLM turn stays valid.
                    for (int j = i + 1; j < batch.size(); j++) {
                        conversations.append(fingerprint, conversationId, toolResultMessage(batch.get(j),
                                "{\"status\":\"not_executed\",\"detail\":\"Deferred: an earlier action in this"
                                        + " turn required officer confirmation. Ask again if still needed.\"}"));
                    }
                    sink.emit(StreamEvent.actionCard(
                            approval.cardId(), tool.name(), normalized.arguments(), approval.humanSummary(),
                            approval.idempotencyKey(), approval.expiresAt().toString(), rows));
                    return; // Paused: no done event; the decision endpoint continues this turn.
                }
                ExecStatus readStatus = executeAndRecord(tool, call, context, null, conversationId,
                        fingerprint, sink, Map.of());
                if (readStatus == ExecStatus.AUTH_FAILED) {
                    return; // Auth expired, so the turn already ended with AUTH_EXPIRED + done.
                }
                if (readStatus == ExecStatus.UNKNOWN) {
                    // Only writes are marked indeterminate, so this is a read that timed out.
                    sink.emit(StreamEvent.error(ErrorCode.TOOL_FAILED,
                            "The banking system did not answer in time. Please try again.", true));
                    sink.emit(StreamEvent.done(conversationId));
                    return;
                }
                if (sink.isCancelled()) {
                    return;
                }
            }
        }
        sink.emit(StreamEvent.error(ErrorCode.TOOL_FAILED,
                "I could not finish this request within the allowed number of steps. Please rephrase it.", false));
        sink.emit(StreamEvent.done(conversationId));
    }

    private ExecStatus executeAndRecord(ToolDefinition tool, LlmToolCall call, CallContext context,
            String idempotencyKey, String conversationId, String fingerprint, EventSink sink,
            Map<String, String> rows) {
        sink.emit(StreamEvent.toolCall(tool.name(), "started", !tool.write(), -1));
        long startedAt = System.currentTimeMillis();
        String outcome;
        try {
            outcome = executor.execute(tool, call.arguments(), context, idempotencyKey);
        } catch (ToolExecutionException e) {
            if (e.isAuthFailure()) {
                sink.emit(StreamEvent.error(ErrorCode.AUTH_EXPIRED,
                        "Your session expired. Please sign in again and retry.", true));
                sink.emit(StreamEvent.done(conversationId));
                return ExecStatus.AUTH_FAILED;
            } else if (e.isIndeterminate()) {
                sink.emit(StreamEvent.toolCall(tool.name(), "finished", !tool.write(),
                        System.currentTimeMillis() - startedAt));
                return ExecStatus.UNKNOWN;
            } else if (e.isPermissionFailure()) {
                outcome = "{\"error\":\"Your Mifos X role does not permit this operation.\"}";
            } else {
                outcome = "{\"error\":" + jsonQuote(e.getMessage()) + "}";
            }
        } catch (RuntimeException e) {
            // Something went wrong building the request, so nothing was sent. Report it as a
            // rejection, which is what it is, rather than letting it escape and end the turn
            // with no explanation and the card already spent.
            outcome = "{\"error\":" + jsonQuote(e.getMessage() == null ? "The request could not be prepared."
                    : e.getMessage()) + "}";
        }
        sink.emit(StreamEvent.toolCall(tool.name(), "finished", !tool.write(), System.currentTimeMillis() - startedAt));
        conversations.append(fingerprint, conversationId, toolResultMessage(call, outcome));
        emitNavigationCard(tool, call, outcome, sink, rows);
        return isErrorOutcome(outcome) ? ExecStatus.APP_ERROR : ExecStatus.OK;
    }

    private boolean isErrorOutcome(String outcome) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(outcome).has("error");
        } catch (Exception e) {
            return false; // Non-JSON tool output counts as success; the model interprets it.
        }
    }

    /**
     * After a successful create, give the officer a one-click path to the new record:
     * a display card with a route button into the web-app (never an approval card).
     */
    private void emitNavigationCard(ToolDefinition tool, LlmToolCall call, String outcome, EventSink sink,
            Map<String, String> rows) {
        try {
            com.fasterxml.jackson.databind.JsonNode result = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(outcome);
            boolean failed = result.has("error");
            // Ids come from the Fineract response when it succeeded, from the call's own
            // arguments otherwise, so even a FAILED action offers a "go check directly" link.
            long clientId = result.path("clientId").asLong(argAsLong(call, "clientId"));
            long loanId = result.path("loanId").asLong(argAsLong(call, "loanId"));
            switch (tool.name()) {
                case "mifos_client_create" -> {
                    long id = result.path("clientId").asLong(result.path("resourceId").asLong(0));
                    if (!failed && id > 0) {
                        String named = nameFrom(call, "firstname", "lastname");
                        sink.emit(StreamEvent.displayCard("client", "Client created",
                                receipt(rows, named.isBlank() ? "Client account" : "Client",
                                        named.isBlank() ? String.valueOf(id) : named),
                                List.of(Map.of("label", "Open client profile", "style", "primary",
                                        "route", "/clients/" + id + "/general"))));
                    }
                }
                case "mifos_loan_create" -> {
                    if (!failed && loanId > 0 && clientId > 0) {
                        sink.emit(StreamEvent.displayCard("loan", "Loan application submitted",
                                receipt(rows, "Loan account", String.valueOf(loanId)),
                                List.of(Map.of("label", "Open loan", "style", "primary",
                                        "route", "/clients/" + clientId + "/loans-accounts/" + loanId + "/general"))));
                    } else if (failed && clientId > 0) {
                        sink.emit(StreamEvent.displayCard("insight", "Loan application was not created",
                                receipt(rows, "Client account", String.valueOf(clientId)),
                                List.of(Map.of("label", "Open client to verify", "style", "accent",
                                        "route", "/clients/" + clientId + "/general"))));
                    }
                }
                case "mifos_loan_approve", "mifos_loan_disburse", "mifos_loan_repayment" -> {
                    // Fineract loan-command responses include clientId + loanId on success.
                    if (!failed && loanId > 0 && clientId > 0) {
                        sink.emit(StreamEvent.displayCard("loan", "Loan updated",
                                receipt(rows, "Loan account", String.valueOf(loanId)),
                                List.of(Map.of("label", "Open loan", "style", "primary",
                                        "route", "/clients/" + clientId + "/loans-accounts/" + loanId + "/general"))));
                    }
                }
                default -> {
                    // Reads: the model prose suffices.
                }
            }
        } catch (Exception e) {
            // Navigation is a convenience, so never let it break the turn.
        }
    }

    private long argAsLong(LlmToolCall call, String name) {
        Object value = call.arguments() == null ? null : call.arguments().get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }


    /**
     * The rows the officer reads: what the account actually is, then what is about to change.
     * Identifiers are left out, since the enrichment already names the account and product.
     */
    private Map<String, String> cardRows(ToolDefinition tool, LlmToolCall call, Map<String, String> enriched,
            CallContext context) {
        Map<String, String> rows = new LinkedHashMap<>(enriched);
        // The enrichment reports the currency of the record being changed, so the amount the
        // officer approves is denominated the same way as the account it lands on.
        String currency = rows.getOrDefault(Display.CURRENCY, "");
        rows.keySet().removeIf(Display::isReserved);
        for (ToolDefinition.Param param : tool.params() == null ? List.<ToolDefinition.Param>of() : tool.params()) {
            if (!param.show()) {
                continue;
            }
            Object raw = call.arguments() == null ? null : call.arguments().get(param.name());
            if (raw == null || String.valueOf(raw).isBlank()) {
                continue;
            }
            String value = String.valueOf(raw);
            if (param.isMoney()) {
                try {
                    value = Display.money(Double.parseDouble(value), currency);
                } catch (NumberFormatException e) {
                    // Leave it as the model supplied it rather than hiding the value.
                }
            } else if (param.isDate()) {
                value = Display.date(value, executor.businessDate(context));
            }
            rows.put(param.displayLabel(), value);
        }
        return rows;
    }

    /** Fill the manifest's summary template from the enriched names, then the raw arguments. */
    private String summaryFor(ToolDefinition tool, LlmToolCall call, Map<String, String> enriched) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (call.arguments() != null) {
            values.putAll(call.arguments());
        }
        enriched.forEach((label, value) -> {
            if (!Display.isReserved(label)) {
                values.put(templateKey(label), value);
            }
        });
        values.putIfAbsent("productName", enriched.getOrDefault("Product", ""));
        values.putIfAbsent("clientName", enriched.getOrDefault("Client", ""));
        String summary = tidy(tool.humanSummary(values));
        // Enrichment is best-effort, so a template built entirely from names can collapse
        // to a single word. A vague title on a money card is worse than a wordy one.
        return summary.split(" ").length >= 3 ? summary : firstSentence(tool.description());
    }

    /** Enough of the tool's own description to say what is about to happen. */
    private String firstSentence(String description) {
        if (description == null || description.isBlank()) {
            return "Confirm this action";
        }
        int stop = description.indexOf(". ");
        return stop > 0 ? description.substring(0, stop) : description;
    }

    /**
     * Drops placeholders the enrichment could not fill and the double spaces they leave behind,
     * so a literal "{clientName}" never reaches an officer.
     */
    private String tidy(String summary) {
        StringBuilder out = new StringBuilder(summary.length());
        for (int i = 0; i < summary.length(); i++) {
            char c = summary.charAt(i);
            if (c == '{') {
                int close = summary.indexOf('}', i);
                if (close > i) {
                    i = close;
                    continue;
                }
            }
            if (c == ' ' && (out.isEmpty() || out.charAt(out.length() - 1) == ' ')) {
                continue;
            }
            out.append(c);
        }
        String trimmed = out.toString().trim();
        return trimmed.endsWith(" for") ? trimmed.substring(0, trimmed.length() - 4).trim() : trimmed;
    }

    /** "Loan account" becomes "loanAccount", so manifest labels can be used in summary templates. */
    private String templateKey(String label) {
        StringBuilder key = new StringBuilder(label.length());
        boolean startOfWord = false;
        for (char c : label.trim().toCharArray()) {
            if (c == ' ') {
                startOfWord = !key.isEmpty();
                continue;
            }
            key.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
            startOfWord = false;
        }
        return key.toString();
    }


    /**
     * What the receipt says about the record that just changed.
     *
     * <p>Reuses the rows the officer read on the confirmation card, so the receipt names the
     * same client and account they approved. Enrichment is best-effort, so when it found
     * nothing the receipt falls back to the identifier under a label. An id is a poor thing
     * to show an officer, but a receipt with no row at all is worse: there would be no way to
     * tell which record had just changed.
     */
    private Map<String, String> receipt(Map<String, String> rows, String preferred, String fallback) {
        Map<String, String> named = new LinkedHashMap<>();
        if (rows != null) {
            for (Map.Entry<String, String> row : rows.entrySet()) {
                if (NAMING_ROWS.contains(row.getKey())) {
                    named.put(row.getKey(), row.getValue());
                }
            }
        }
        if (named.isEmpty() && fallback != null && !fallback.isBlank()) {
            named.put(preferred, fallback);
        }
        return named;
    }

    /** Rows worth repeating on a receipt: who and which account, not the figures again. */
    private static final java.util.Set<String> NAMING_ROWS =
            java.util.Set.of("Client", "Client account", "Loan account", "Savings account", "Product");

    /** "Aisha Bello" from the arguments of a create, which has no record to read back yet. */
    private String nameFrom(LlmToolCall call, String... parts) {
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            Object value = call.arguments() == null ? null : call.arguments().get(part);
            if (value != null && !String.valueOf(value).isBlank()) {
                name.append(name.isEmpty() ? "" : " ").append(value);
            }
        }
        return name.toString();
    }

    /**
     * Whether the last recorded outcome was a refusal raised before anything reached Fineract.
     *
     * <p>Those are worth putting the card back for: nothing ran, and the officer may be able
     * to correct what caused it. A refusal from Fineract itself is a decision, and the card
     * stays spent.
     */
    private boolean nothingWasSent(String conversationId, String fingerprint) {
        List<Map<String, Object>> messages = conversations.messages(fingerprint, conversationId);
        if (messages.isEmpty()) {
            return false;
        }
        Object content = messages.get(messages.size() - 1).get("content");
        String text = content == null ? "" : String.valueOf(content);
        return text.contains("could not be prepared") || text.contains("not one you can work in");
    }

    /**
     * What to tell the officer when the model did not answer.
     *
     * <p>Three different things happen here and they used to produce two sentences between
     * them. A refusal will not come right by waiting. An outage might. Being throttled
     * certainly will, and the provider usually says when, which is the difference between a
     * sentence somebody can act on and one they can only stare at.
     */
    private StreamEvent llmErrorEvent(LlmException e) {
        if (e.isClientError()) {
            return StreamEvent.error(ErrorCode.LLM_UNAVAILABLE,
                    "The AI model rejected this request. Retrying will not help, so please tell your"
                            + " administrator.", false);
        }
        if (e.isRateLimited()) {
            int wait = e.retryAfterSeconds();
            return StreamEvent.error(ErrorCode.RATE_LIMITED,
                    wait > 0
                            ? "That was a lot of questions at once. Try again in " + spellWait(wait) + "."
                            : "That was a lot of questions at once. Please wait a moment and try again.",
                    true);
        }
        return StreamEvent.error(ErrorCode.LLM_UNAVAILABLE,
                "The AI model is unavailable right now. Please try again shortly.", true);
    }

    /**
     * A wait in the units a person would use for it.
     *
     * <p>Seconds are right up to a point, and past that they stop being a quantity anybody
     * reads: nobody counts out a hundred and fifty of them. Rounded up for the same reason the
     * wait itself is, so the officer is never sent back early.
     */
    private static String spellWait(int seconds) {
        if (seconds <= 90) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        int minutes = (int) Math.ceil(seconds / 60d);
        if (minutes <= 90) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        int hours = (int) Math.ceil(minutes / 60d);
        return hours + (hours == 1 ? " hour" : " hours");
    }

    /** The problems as a JSON array, so the model reads them as a list rather than a sentence. */
    private String jsonArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            out.append(i == 0 ? "" : ",").append(jsonQuote(values.get(i)));
        }
        return out.append("]").toString();
    }

    /** Argument names the model supplied that the manifest does not declare for this tool. */
    private List<String> undeclaredArguments(ToolDefinition tool, LlmToolCall call) {
        if (call.arguments() == null || call.arguments().isEmpty()) {
            return List.of();
        }
        List<String> declared = tool.params() == null ? List.of()
                : tool.params().stream().map(ToolDefinition.Param::name).toList();
        return call.arguments().keySet().stream().filter((name) -> !declared.contains(name)).toList();
    }

    private List<Map<String, Object>> withSystemPrompt(Map<String, Object> screenContext,
            List<Map<String, Object>> history, CallContext context) {
        List<Map<String, Object>> messages = new ArrayList<>();
        // The model must date commands from the CORE BANKING business date, which can differ
        // from this host's clock; Fineract rejects anything dated in its future.
        messages.add(Map.of("role", "system",
                "content", SystemPrompt.build(screenContext, executor.businessDate(context))));
        synchronized (history) {
            messages.addAll(history);
        }
        return messages;
    }

    private Map<String, Object> assistantToolCallMessage(List<LlmToolCall> calls) {
        List<Map<String, Object>> encoded = calls.stream().map((call) -> Map.<String, Object>of(
                "id", call.id() == null ? "call-0" : call.id(),
                "type", "function",
                "function", Map.of("name", call.name(), "arguments",
                        encodeArguments(manifest.find(call.name()).orElse(null), call.arguments())))).toList();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", "");
        message.put("tool_calls", encoded);
        return message;
    }

    private Map<String, Object> toolResultMessage(LlmToolCall call, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", call.id() == null ? "call-0" : call.id());
        message.put("content", content);
        return message;
    }

    /**
     * The arguments as the model will see them again, with the private ones masked.
     *
     * <p>What a tool declares private is masked in the answer Fineract gives, and was not
     * masked in the record of what was asked. That record is replayed to the model on every
     * later round of the same conversation, so a phone number went out once as an argument
     * and then again on every turn that followed.
     *
     * <p>The officer still sees the real values, on the card, which is where they check them.
     */
    private String encodeArguments(ToolDefinition tool, Map<String, Object> arguments) {
        if (tool == null || tool.redactFields().isEmpty() || arguments == null) {
            return encodeArguments(arguments);
        }
        Map<String, Object> masked = new LinkedHashMap<>(arguments);
        for (String field : tool.redactFields()) {
            if (masked.get(field) != null) {
                masked.put(field, "•••");
            }
        }
        return encodeArguments(masked);
    }

    private String encodeArguments(Map<String, Object> arguments) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private String jsonQuote(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value == null ? "" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "\"tool failed\"";
        }
    }
}
