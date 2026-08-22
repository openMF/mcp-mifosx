/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import org.mifos.community.copilot.core.auth.CallContext;

import java.util.Map;

/**
 * Executes one manifest tool as the calling officer.
 *
 * <p>Implementations MUST forward {@code context.authorizationHeader()} and tenant so Fineract
 * RBAC + audit apply to the real user, and MUST send {@code idempotencyKey} (when present) as
 * Fineract's {@code Idempotency-Key} header so approved writes are exactly-once.
 */
public interface ToolExecutor {

    /**
     * @return the tool's response body (JSON) to feed back to the model
     * @throws ToolExecutionException on transport failure or a non-2xx Fineract response
     */
    String execute(ToolDefinition tool, Map<String, Object> args, CallContext context, String idempotencyKey)
            throws ToolExecutionException;

    /**
     * The date the core banking system considers "today" (yyyy-MM-dd).
     *
     * <p>Fineract runs on a configurable business date that can differ from the gateway host's
     * clock. Commands dated in Fineract's future are rejected, so both the system prompt and
     * date-parameter resolution must use THIS date, not {@code LocalDate.now()}.
     */
    default String businessDate(CallContext context) {
        return java.time.LocalDate.now().toString();
    }

    /**
     * Read the human context for a pending write, so the confirmation card can name the
     * account, the product and the client rather than repeating identifiers back.
     *
     * <p>Best effort by design: this is presentation, so a failure returns an empty map and the
     * card falls back to the tool's declared parameters instead of failing the turn.
     *
     * @return card row label to display-ready value, in the manifest's declared order
     */
    default java.util.Map<String, String> enrich(ToolDefinition tool, java.util.Map<String, Object> args,
            CallContext context) {
        return java.util.Map.of();
    }
}
