/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.approval;

import org.mifos.community.copilot.core.llm.LlmToolCall;

import java.time.Instant;
import java.util.Map;

/**
 * A write action paused for human confirmation (ADR-001 §04).
 *
 * <p>{@code securityFingerprint} binds the card to the asking officer and tenant, so only the same
 * identity may decide it. {@code idempotencyKey} is minted HERE, server-side, at card creation;
 * client-supplied keys are ignored by design. No raw credential is ever stored.
 *
 * <p>{@code rows} are the labelled values the officer read before confirming. They are kept so
 * the receipt shown afterwards names the same account, rather than falling back to an id.
 */
public record PendingApproval(String cardId, String conversationId, LlmToolCall toolCall, String humanSummary,
        String idempotencyKey, String securityFingerprint, Instant expiresAt, Map<String, String> rows) {

    /**
     * Copied on the way in, so the receipt cannot drift from the card. A caller holding the
     * map it passed could otherwise change what an officer is recorded as having approved,
     * and restore() puts a card back without going through create() again.
     */
    public PendingApproval {
        rows = rows == null ? Map.of() : Map.copyOf(rows);
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
