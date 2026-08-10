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

    public ToolExecutionException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
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
}
