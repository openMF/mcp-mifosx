/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.llm;

/** LLM provider failure: unreachable, timed out, or rejected the request. */
public class LlmException extends Exception {

    private final boolean rateLimited;

    public LlmException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public LlmException(String message, Throwable cause, boolean rateLimited) {
        super(message, cause);
        this.rateLimited = rateLimited;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }
}
