/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

/** Tool execution failed: transport error or a non-2xx Fineract response. */
public class ToolExecutionException extends Exception {

    private final int statusCode;
    private final boolean indeterminate;

    public ToolExecutionException(String message, int statusCode, Throwable cause) {
        this(message, statusCode, cause, false);
    }

    public ToolExecutionException(String message, int statusCode, Throwable cause, boolean indeterminate) {
        super(message, cause);
        this.statusCode = statusCode;
        this.indeterminate = indeterminate;
    }

    /** Fineract HTTP status, or 0 when the call never reached Fineract. */
    public int statusCode() {
        return statusCode;
    }

    public boolean isAuthFailure() {
        return statusCode == 401;
    }

    public boolean isPermissionFailure() {
        return statusCode == 403;
    }

    /**
     * True when the request may have been carried out despite the failure.
     *
     * <p>A read timeout is not a rejection. A disbursement that regenerates a schedule and
     * posts accruals can take longer than the client will wait, and the write commits anyway.
     * Telling an officer their disbursement failed when it succeeded invites them to do it
     * again, and the second one is a second payment.
     */
    public boolean isIndeterminate() {
        return indeterminate;
    }
}
