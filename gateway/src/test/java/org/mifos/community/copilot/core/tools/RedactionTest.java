/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a rejected write is allowed to tell the model about the client.
 *
 * <p>A tool declares which of its fields are private, and the officer's own banking system is
 * the thing that hands them back. Fineract does not answer a rejection with a tidy object
 * keyed by parameter name. It answers in prose, with the submitted value written into the
 * sentence, and again under a value field beside the parameter's name. Masking by key alone
 * reads that payload, finds no key called mobileNo, and passes the number through to a model
 * that may not be running on the bank's own hardware.
 */
class RedactionTest {

    private static final String BASE_URL = "https://example.invalid";

    /** Verbatim in shape from Fineract's duplicate handling, which echoes what was sent. */
    private static final String DUPLICATE_MOBILE = """
            {"developerMessage":"The request caused a data integrity issue to be fired by the database.",
             "httpStatusCode":"403",
             "defaultUserMessage":"Client with mobileNo `0712345678` already exists",
             "userMessageGlobalisationCode":"error.msg.client.duplicate.mobileNo",
             "errors":[{"developerMessage":"Client with mobileNo `0712345678` already exists",
                        "defaultUserMessage":"Client with mobileNo `0712345678` already exists",
                        "userMessageGlobalisationCode":"error.msg.client.duplicate.mobileNo",
                        "parameterName":"mobileNo",
                        "value":"0712345678",
                        "args":[{"value":"0712345678"}]}]}
            """;

    @Test
    void aRejectionDoesNotHandTheClientsPhoneNumberToTheModel() {
        FineractRestToolExecutor executor = new FineractRestToolExecutor(BASE_URL);

        String safe = executor.redact(DUPLICATE_MOBILE, List.of("mobileNo", "dateOfBirth", "externalId"),
                Map.of("firstname", "Grace", "lastname", "Wanjiru", "mobileNo", "0712345678"));

        assertThat(safe).as("not in the prose, not in the value field, not in the args")
                .doesNotContain("0712345678");
        assertThat(safe).as("the officer still learns what went wrong").contains("already exists");
    }

    @Test
    void aNationalIdIsMaskedTheSameWayAPhoneNumberIs() {
        FineractRestToolExecutor executor = new FineractRestToolExecutor(BASE_URL);
        String body = "{\"defaultUserMessage\":\"Client with externalId `NID-88213944` already exists\","
                + "\"errors\":[{\"parameterName\":\"externalId\",\"value\":\"NID-88213944\"}]}";

        String safe = executor.redact(body, List.of("externalId"), Map.of("externalId", "NID-88213944"));

        assertThat(safe).doesNotContain("NID-88213944");
    }

    @Test
    void maskingByKeyStillWorksForAPlainRead() {
        FineractRestToolExecutor executor = new FineractRestToolExecutor(BASE_URL);
        String body = "{\"id\":42,\"displayName\":\"Grace Wanjiru\",\"mobileNo\":\"0712345678\"}";

        String safe = executor.redact(body, List.of("mobileNo"), Map.of());

        assertThat(safe).doesNotContain("0712345678");
        assertThat(safe).as("the rest of the record survives").contains("Grace Wanjiru");
    }

    /**
     * A value short enough that masking it everywhere would do more harm than good.
     *
     * <p>An officer whose client id is 1 does not want every 1 in the message replaced. The
     * point is to stop a phone number or a national id reaching the model, and those are not
     * three characters long.
     */
    @Test
    void aVeryShortValueIsNotMaskedOutOfTheWholeMessage() {
        FineractRestToolExecutor executor = new FineractRestToolExecutor(BASE_URL);
        String body = "{\"defaultUserMessage\":\"Loan 100 is not in a state to be approved on 2026-08-23\"}";

        String safe = executor.redact(body, List.of("mobileNo"), Map.of("mobileNo", "100"));

        assertThat(safe).as("the message is still readable").contains("not in a state to be approved");
    }
}
