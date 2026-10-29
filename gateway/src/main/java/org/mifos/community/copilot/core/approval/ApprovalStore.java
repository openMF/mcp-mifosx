/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.approval;

import org.mifos.community.copilot.core.auth.CallContext;
import org.mifos.community.copilot.core.audit.ActionAudit;
import org.mifos.community.copilot.core.audit.ActionAudit.State;
import org.mifos.community.copilot.core.llm.LlmToolCall;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, single-use store of write actions awaiting confirmation.
 *
 * <p>Take-semantics make double-approval impossible: the first decision consumes the card, a
 * second click finds nothing. Cards expire after {@link #ttl} and are swept lazily.
 */
public final class ApprovalStore {

    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final ActionAudit audit;

    /** Legacy gateway approvals only. MCP proposals require an explicit durable audit store. */
    public ApprovalStore(Duration ttl) {
        this(ttl, null);
    }

    public ApprovalStore(Duration ttl, ActionAudit audit) {
        this.ttl = ttl;
        this.audit = audit;
    }

    private boolean audited(PendingApproval approval) {
        return "create_client".equals(approval.toolCall().name()) && approval.session().containsKey("mcpSessionId");
    }

    private ActionAudit audit() {
        if (audit == null) { throw new IllegalStateException("Action audit unavailable"); }
        return audit;
    }

    private void transition(PendingApproval approval, State state) {
        if (audited(approval)) { audit().transition(approval.cardId(), state, null); }
    }

    /** A session reference is safe to persist; the live transport ID and decision ticket are not retained. */
    private static String sessionReference(String session) {
        return new CallContext(session, "", "").fingerprint();
    }

    public void complete(String cardId, CallContext context, String session, ActionAudit.Outcome outcome) {
        var entry = audit().find(cardId).orElseThrow(() -> new IllegalStateException("Unknown action"));
        if (!context.hasCredential() || !entry.callerFingerprint().equals(context.fingerprint())
                || !entry.tenant().equals(context.tenantId()) || !entry.sessionReference().equals(sessionReference(session))) {
            throw new IllegalArgumentException("Action ownership mismatch");
        }
        audit().transition(cardId, outcome.success() ? State.succeeded : State.failed, outcome);
    }

    /** A callback blocked before the claim is known not to have executed. After claim,
     * a lost callback is ambiguous: preserve executing rather than inventing a result. */
    public void callbackBlocked(String cardId, CallContext context, String session) {
        pending.computeIfPresent(cardId, (id, approval) -> {
            if (!owned(approval, context) || !session.equals(approval.session().get("mcpSessionId"))) {
                throw new IllegalArgumentException("Action ownership mismatch");
            }
            if (approval.claimToken() == null) { return approval; }
            audit().transition(cardId, State.failed, new ActionAudit.Outcome(false, 0, "execution_blocked"));
            return null;
        });
    }

    /** Create and register a card for this tool call, bound to the asking identity. */
    public PendingApproval create(String conversationId, LlmToolCall call, String humanSummary, CallContext context,
            java.util.Map<String, String> rows) {
        sweep();
        PendingApproval approval = new PendingApproval(
                "card-" + UUID.randomUUID(),
                conversationId,
                call,
                humanSummary,
                "cop-" + UUID.randomUUID(), // Server-minted idempotency key, the only authority.
                context.fingerprint(),
                Instant.now().plus(ttl),
                rows == null ? java.util.Map.of() : java.util.Map.copyOf(rows),
                context.session());
        if (audited(approval)) {
            String now = Instant.now().toString();
            audit().create(new ActionAudit.Entry(approval.cardId(), "create_client", context.tenantId(),
                    approval.securityFingerprint(), sessionReference((String) approval.session().get("mcpSessionId")),
                    State.proposed, now, now, null, null));
        }
        pending.put(approval.cardId(), approval);
        return approval;
    }

    /**
     * Put a consumed card back after a retryable execution failure (e.g. the officer's
     * session expired mid-decision). The server-minted idempotency key is preserved, so a
     * retried execution stays exactly-once even if the failed attempt partially ran.
     */
    public void restore(PendingApproval approval) {
        if (!audited(approval) && !approval.isExpired(Instant.now())) {
            pending.put(approval.cardId(), approval);
        }
    }

    /**
     * Drop every card still waiting in this conversation, and say which calls they answered for.
     *
     * <p>Called when the officer moves on without deciding. Two things have to happen together:
     * the abandoned card must stop being decidable, because approving it later would execute an
     * action out of order against a conversation that has moved past it, and the tool call it
     * was holding open has to be closed off. Doing only the second would leave a live card
     * pointing at a call the history has already settled.
     */
    public void discardFor(String conversationId, CallContext context) {
        pending.values().removeIf((approval) -> !audited(approval) && approval.conversationId().equals(conversationId)
                && approval.securityFingerprint().equals(context.fingerprint()));
    }

    /**
     * Atomically consume the card. Returns empty when unknown, already decided, expired, or
     * presented by a different user/tenant than the one who asked. A fingerprint mismatch is
     * SIDE-EFFECT-FREE: a stranger probing a card id must not be able to destroy the rightful
     * officer's pending approval.
     */
    public Optional<PendingApproval> take(String cardId, CallContext context) {
        // MCP cards may only be consumed by the session-bound bridge, never AgentLoop.
        return take(cardId, context, a -> !a.session().containsKey("mcpSessionId"));
    }

    public Optional<PendingApproval> take(String cardId, CallContext context,
            java.util.function.Predicate<PendingApproval> additionalChecks) {
        return consume(cardId, context, additionalChecks, State.executing);
    }

    public Optional<PendingApproval> reject(String cardId, CallContext context,
            java.util.function.Predicate<PendingApproval> additionalChecks) {
        return consume(cardId, context, additionalChecks, State.rejected);
    }

    private Optional<PendingApproval> consume(String cardId, CallContext context,
            java.util.function.Predicate<PendingApproval> checks, State state) {
        var taken = new java.util.concurrent.atomic.AtomicReference<PendingApproval>();
        pending.computeIfPresent(cardId, (id, approval) -> {
            if (approval.isExpired(Instant.now())) { transition(approval, State.expired); return null; }
            if (!owned(approval, context) || !checks.test(approval)) { return approval; }
            transition(approval, state); // Persistence failure leaves the card unconsumed.
            taken.set(approval);
            return null;
        });
        return Optional.ofNullable(taken.get());
    }

    /** Mark a human decision before handing a single-use claim ticket to an executor.
     * The ticket never reaches the model. Confirmation does not extend expiry. */
    public Optional<String> confirm(String cardId, CallContext context,
            java.util.function.Predicate<PendingApproval> additionalChecks) {
        java.util.concurrent.atomic.AtomicReference<String> ticket = new java.util.concurrent.atomic.AtomicReference<>();
        pending.computeIfPresent(cardId, (id, approval) -> {
            if (approval.isExpired(Instant.now())) { transition(approval, State.expired); return null; }
            if (!owned(approval, context) || !additionalChecks.test(approval) || approval.claimToken() != null) {
                return approval;
            }
            String token = UUID.randomUUID().toString();
            transition(approval, State.approved);
            ticket.set(token);
            return new PendingApproval(approval.cardId(), approval.conversationId(), approval.toolCall(),
                    approval.humanSummary(), approval.idempotencyKey(), approval.securityFingerprint(),
                    approval.expiresAt(), approval.rows(), approval.session(), token);
        });
        return Optional.ofNullable(ticket.get());
    }

    private boolean owned(PendingApproval approval, CallContext context) {
        return context.hasCredential() && context.tenantId() != null && !context.tenantId().isBlank()
                && approval.securityFingerprint().equals(context.fingerprint());
    }

    private void sweep() {
        Instant now = Instant.now();
        for (String id : pending.keySet()) {
            pending.computeIfPresent(id, (key, approval) -> {
                if (approval.isExpired(now)) { transition(approval, State.expired); return null; }
                return approval;
            });
        }
    }
}
