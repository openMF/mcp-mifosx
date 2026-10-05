/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.ai.decision.dto;

/**
 * Structured decision returned by the Clef decision model.
 */
public record DocumentEvaluationResponse(
        boolean isBankAccountStatement,
        double probability,
        String decisionLabel,
        String model,
        String explanation,
        /** Original filename when the request was a binary upload; null for pure-text calls. */
        String sourceFilename,
        /** Content type of the uploaded binary, if any. */
        String sourceContentType
) {
    /** Convenience constructor for text-only evaluations. */
    public DocumentEvaluationResponse(
            boolean isBankAccountStatement,
            double probability,
            String decisionLabel,
            String model,
            String explanation) {
        this(isBankAccountStatement, probability, decisionLabel, model, explanation, null, null);
    }
}
