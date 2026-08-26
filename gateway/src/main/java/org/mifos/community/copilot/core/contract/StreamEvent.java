/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Server-Sent Event of wire contract v1 (ADR-001 §03).
 *
 * <p>{@code name} becomes the SSE {@code event:} field; {@code data} is JSON-encoded into the
 * {@code data:} field. Payload keys are snake_case exactly as the frontend's parser expects.
 */
public record StreamEvent(String name, Map<String, Object> data) {

    public static StreamEvent token(String delta) {
        return new StreamEvent("token", Map.of("delta", delta));
    }

    /**
     * A fragment of the model's reasoning, on its own channel so it is never mistaken for
     * advice.
     *
     * <p>Sent separately from {@code token} rather than mixed into it, because the two are
     * different kinds of thing: one is what the assistant is telling the officer, the other is
     * how it got there. A client that does not know this event ignores it and shows the answer
     * exactly as before.
     */
    public static StreamEvent thinkingStart() {
        return new StreamEvent("thinking", Map.of("phase", "start"));
    }

    /**
     * A fragment of the model's working notes.
     *
     * <p>Deliberately not called reasoning or explanation on the wire. Chain of thought is not
     * a faithful account of how a model reached an answer, and naming it as one here would
     * put that claim into every client that ever reads this contract.
     */
    public static StreamEvent thinkingDelta(String delta) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", "delta");
        data.put("delta", delta);
        return new StreamEvent("thinking", data);
    }

    public static StreamEvent thinkingEnd(long elapsedMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", "end");
        data.put("elapsed_ms", elapsedMs);
        return new StreamEvent("thinking", data);
    }

    public static StreamEvent toolCall(String tool, String phase, boolean readOnly, long durationMs) {
        return toolCall(tool, tool, phase, readOnly, durationMs);
    }

    /**
     * A step in what the gateway did, named the way an officer would say it.
     *
     * <p>{@code label} comes from the tool manifest, never from a model. A step log is part of
     * the record of what happened on a client's account, and a record whose wording changes
     * between runs is not much of a record.
     */
    public static StreamEvent toolCall(String tool, String label, String phase, boolean readOnly, long durationMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", tool);
        data.put("label", label);
        data.put("phase", phase);
        data.put("read_only", readOnly);
        if (durationMs >= 0) {
            data.put("duration_ms", durationMs);
        }
        return new StreamEvent("tool_call", data);
    }

    /**
     * Approval card, built by the server from the parsed function call and never from model
     * prose.
     *
     * <p>{@code rows} is what the officer actually reads: labelled, formatted, and naming the
     * account, product and client. {@code args} stays alongside it as the machine record of
     * exactly what will execute.
     */
    public static StreamEvent actionCard(String cardId, String tool, Map<String, Object> args, String humanSummary,
            String idempotencyKey, String expiresAt, Map<String, String> rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("card_id", cardId);
        data.put("tool", tool);
        data.put("args", args);
        data.put("rows", rows == null ? Map.of() : rows);
        data.put("human_summary", humanSummary);
        data.put("idempotency_key", idempotencyKey);
        data.put("expires_at", expiresAt);
        return new StreamEvent("action_card", data);
    }

    /** Display-only card (no approval semantics): rendered under the message, may carry route buttons. */
    public static StreamEvent displayCard(String type, String title, Map<String, String> data,
            List<Map<String, Object>> actions) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", type);
        card.put("title", title);
        card.put("data", data);
        if (actions != null && !actions.isEmpty()) {
            card.put("actions", actions);
        }
        return new StreamEvent("action_card", Map.of("card", card));
    }

    public static StreamEvent suggest(List<String> items) {
        return new StreamEvent("suggest", Map.of("items", items));
    }

    public static StreamEvent done(String conversationId) {
        return new StreamEvent("done", Map.of("conversation_id", conversationId));
    }

    public static StreamEvent error(ErrorCode code, String message, boolean retryable) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code.name());
        data.put("message", message);
        data.put("retryable", retryable);
        return new StreamEvent("error", data);
    }
}
