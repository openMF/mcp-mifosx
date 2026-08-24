/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What every tool says is private, written down in one place on purpose.
 *
 * <p>A tool that declares no {@code redactFields} is silently allowed to send whatever
 * Fineract hands back to a model that may not be running on the bank's own hardware. That is
 * the right answer for a list of loan products and the wrong one for a client record, and
 * nothing in the code can tell those apart. Omitting the key looks exactly like deciding
 * there is nothing to hide.
 *
 * <p>So the decision is pinned here instead. Adding a tool, or removing a mask from one, fails
 * this test until somebody comes and says which it is. That is the whole point: the omission
 * has to be deliberate rather than accidental, and the reasoning survives in the diff.
 */
class RedactionPostureTest {

    private static final ToolManifest MANIFEST = ToolManifest.load(
            RedactionPostureTest.class.getResourceAsStream("/tools.yaml"));

    /**
     * Every tool, and what it masks. Checked against sandbox.mifos.community responses.
     *
     * <p>The tools masking nothing return account structure, schedules, balances and product
     * configuration. {@code mifos_loan_details} returns {@code clientName} and deliberately
     * keeps it: the confirmation card exists so the officer can see whose loan they are
     * approving, and a card reading "approve the loan for •••" would defeat the pause.
     */
    private static final Map<String, List<String>> EXPECTED = expected();

    private static Map<String, List<String>> expected() {
        Map<String, List<String>> posture = new LinkedHashMap<>();
        // Reads that carry a person.
        posture.put("mifos_client_search", List.of("entityExternalId", "externalId", "entityMobileNo", "mobileNo"));
        posture.put("mifos_client_details", List.of("mobileNo", "dateOfBirth", "externalId"));
        // Writes that echo what was submitted when Fineract rejects them.
        posture.put("mifos_client_create", List.of("mobileNo", "dateOfBirth", "externalId"));
        // Nothing to mask: structure, schedules, balances, product configuration.
        posture.put("mifos_client_accounts", List.of());
        posture.put("mifos_loan_details", List.of());
        posture.put("mifos_loan_schedule", List.of());
        posture.put("mifos_savings_details", List.of());
        posture.put("mifos_loan_products", List.of());
        posture.put("mifos_loan_create", List.of());
        posture.put("mifos_loan_approve", List.of());
        posture.put("mifos_loan_disburse", List.of());
        posture.put("mifos_loan_repayment", List.of());
        return posture;
    }

    @Test
    void everyToolInTheManifestHasHadItsPrivacyDecided() {
        assertThat(MANIFEST.all().stream().map(ToolDefinition::name))
                .as("a new tool needs a line in EXPECTED saying what it masks and why")
                .containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
    }

    @Test
    void noToolHasQuietlyLostAMask() {
        for (Map.Entry<String, List<String>> entry : EXPECTED.entrySet()) {
            assertThat(MANIFEST.find(entry.getKey()).orElseThrow().redactFields())
                    .as("redactFields for %s", entry.getKey())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
    }

    /**
     * The three fields a client record carries that a model has no business seeing.
     *
     * <p>Named here rather than only in the manifest, so adding a fourth to one tool and
     * forgetting the other is caught. They went out unmasked once already, in the prose of a
     * rejection, and the manifest looked correct the whole time.
     */
    @Test
    void everyToolThatTouchesAClientRecordMasksAllThreeOfThem() {
        for (String name : List.of("mifos_client_details", "mifos_client_create")) {
            assertThat(MANIFEST.find(name).orElseThrow().redactFields())
                    .as("%s masks the phone number, the date of birth and the national id", name)
                    .contains("mobileNo", "dateOfBirth", "externalId");
        }
    }
}
