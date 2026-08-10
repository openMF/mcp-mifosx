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
}
