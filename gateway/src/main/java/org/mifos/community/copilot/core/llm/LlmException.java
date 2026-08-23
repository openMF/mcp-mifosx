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
    private final boolean clientError;

    public LlmException(String message, Throwable cause) {
        this(message, cause, false, false);
    }

    public LlmException(String message, Throwable cause, boolean rateLimited) {
        this(message, cause, rateLimited, false);
    }

    public LlmException(String message, Throwable cause, boolean rateLimited, boolean clientError) {
        super(message, cause);
        this.rateLimited = rateLimited;
        this.clientError = clientError;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    /**
     * True when the provider refused the request rather than failing to serve it.
     *
     * <p>A rejected key, a malformed body or a model that does not exist are all settled
     * facts. They do not come right on their own, and presenting them as a passing outage
     * invites an officer to keep trying something that cannot succeed.
     */
    public boolean isClientError() {
        return clientError;
    }
}
