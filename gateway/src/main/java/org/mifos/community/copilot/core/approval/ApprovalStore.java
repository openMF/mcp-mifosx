/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.approval;

import org.mifos.community.copilot.core.auth.CallContext;
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

    public ApprovalStore(Duration ttl) {
        this.ttl = ttl;
    }

    /** Create and register a card for this tool call, bound to the asking identity. */
    public PendingApproval create(String conversationId, LlmToolCall call, String humanSummary, CallContext context) {
        sweep();
        PendingApproval approval = new PendingApproval(
                "card-" + UUID.randomUUID(),
                conversationId,
                call,
                humanSummary,
                "cop-" + UUID.randomUUID(), // Server-minted idempotency key — the only authority.
                context.fingerprint(),
                Instant.now().plus(ttl));
        pending.put(approval.cardId(), approval);
        return approval;
    }

    /**
     * Put a consumed card back after a retryable execution failure (e.g. the officer's
     * session expired mid-decision). The server-minted idempotency key is preserved, so a
     * retried execution stays exactly-once even if the failed attempt partially ran.
     */
    public void restore(PendingApproval approval) {
        if (!approval.isExpired(Instant.now())) {
            pending.put(approval.cardId(), approval);
        }
    }

    /**
     * Atomically consume the card — returns empty when unknown, already decided, expired, or
     * presented by a different user/tenant than the one who asked. A fingerprint mismatch is
     * SIDE-EFFECT-FREE: a stranger probing a card id must not be able to destroy the rightful
     * officer's pending approval.
     */
    public Optional<PendingApproval> take(String cardId, CallContext context) {
        PendingApproval approval = pending.get(cardId);
        if (approval == null) {
            return Optional.empty();
        }
        if (approval.isExpired(Instant.now())) {
            pending.remove(cardId, approval);
            return Optional.empty();
        }
        if (!approval.securityFingerprint().equals(context.fingerprint())) {
            return Optional.empty(); // Different identity — card stays for its owner.
        }
        // Two-arg remove keeps consumption atomic against a concurrent take of the same card.
        return pending.remove(cardId, approval) ? Optional.of(approval) : Optional.empty();
    }

    private void sweep() {
        Instant now = Instant.now();
        pending.values().removeIf((approval) -> approval.isExpired(now));
    }
}
