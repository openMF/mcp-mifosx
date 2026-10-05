/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.ai.decision.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload containing the text content (or OCR text) of a document
 * submitted as part of a loan application.
 */
public record DocumentEvaluationRequest(
        @NotBlank(message = "documentText must not be blank")
        String documentText,

        /**
         * Optional metadata (e.g. filename, applicant id) that can be included
         * in the decision state for richer context.
         */
        String filename,
        String applicantId
) {
    public DocumentEvaluationRequest(String documentText) {
        this(documentText, null, null);
    }
}
