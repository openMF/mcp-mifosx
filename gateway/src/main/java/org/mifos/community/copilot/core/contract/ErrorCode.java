/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.contract;

/** Error codes of wire contract v1 (ADR-001 §03). */
public enum ErrorCode {
    AUTH_EXPIRED,
    PERMISSION_DENIED,
    LLM_UNAVAILABLE,
    TOOL_FAILED,
    RATE_LIMITED,
    CANCELLED,
    INTERNAL
}
