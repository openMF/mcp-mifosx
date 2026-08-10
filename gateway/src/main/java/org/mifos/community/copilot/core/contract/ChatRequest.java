/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.contract;

import java.util.Map;

/**
 * Body of {@code POST /copilot/api/v1/chat} (wire contract v1).
 *
 * <p>{@code clientMsgId} is a client-generated trace id, NOT an idempotency key — the gateway
 * mints authoritative idempotency keys server-side (ADR-001 §04). {@code context} carries what
 * the officer is looking at (clientId, loanId, screen, role, language) and is injected into the
 * system prompt; it is never trusted for authorization.
 */
public record ChatRequest(String conversationId, String message, String clientMsgId, Map<String, Object> context) {}
