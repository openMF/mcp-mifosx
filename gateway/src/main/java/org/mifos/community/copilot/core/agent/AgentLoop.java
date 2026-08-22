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
        conversations.append(fingerprint, conversationId, Map.of("role", "user", "content", userMessage));
        drive(conversationId, screenContext, context, sink);
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
        ExecStatus status = executeAndRecord(tool, call, context, approval.idempotencyKey(), conversationId,
                fingerprint, sink, approval.rows());
        if (status == ExecStatus.AUTH_FAILED) {
            // Auth expired mid-decision: put the card back (same id, same idempotency key) so
            // the officer's retry after re-login succeeds instead of dead-ending.
            approvals.restore(approval);
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
        AUTH_FAILED
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
                sink.emit(StreamEvent.error(
                        e.isRateLimited() ? ErrorCode.RATE_LIMITED : ErrorCode.LLM_UNAVAILABLE,
                        "The AI model is unavailable right now. Please try again shortly.", true));
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
                    Map<String, String> enriched = executor.enrich(tool, call.arguments(), context);
                    Map<String, String> rows = cardRows(tool, call, enriched, context);
                    PendingApproval approval = approvals.create(conversationId, call,
                            summaryFor(tool, call, enriched), context, rows);
                    // OpenAI-style history requires a tool result for EVERY id in the assistant's
                    // tool_calls message. The paused call gets its result on resume; any siblings
                    // after it are marked not-executed NOW so the next LLM turn stays valid.
                    for (int j = i + 1; j < batch.size(); j++) {
                        conversations.append(fingerprint, conversationId, toolResultMessage(batch.get(j),
                                "{\"status\":\"not_executed\",\"detail\":\"Deferred: an earlier action in this"
                                        + " turn required officer confirmation. Ask again if still needed.\"}"));
                    }
                    sink.emit(StreamEvent.actionCard(
                            approval.cardId(), tool.name(), call.arguments(), approval.humanSummary(),
                            approval.idempotencyKey(), approval.expiresAt().toString(), rows));
                    return; // Paused: no done event; the decision endpoint continues this turn.
                }
                if (executeAndRecord(tool, call, context, null, conversationId, fingerprint, sink, Map.of())
                        == ExecStatus.AUTH_FAILED) {
                    return; // Auth expired, so the turn already ended with AUTH_EXPIRED + done.
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
            } else if (e.isPermissionFailure()) {
                outcome = "{\"error\":\"Your Mifos X role does not permit this operation.\"}";
            } else {
                outcome = "{\"error\":" + jsonQuote(e.getMessage()) + "}";
            }
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
                "function", Map.of("name", call.name(), "arguments", encodeArguments(call.arguments())))).toList();
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
