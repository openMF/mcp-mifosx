/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core;

import org.junit.jupiter.api.Test;
import org.mifos.community.copilot.core.tools.ToolDefinition;
import org.mifos.community.copilot.core.tools.ToolManifest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped tools.yaml is the enforcement authority — validate its actual content. */
class ToolManifestTest {

    private final ToolManifest manifest = ToolManifest.load(getClass().getResourceAsStream("/tools.yaml"));

    @Test
    void loadsTheShippedManifest() {
        assertThat(manifest.size()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void unknownToolsResolveEmpty_defaultDeny() {
        assertThat(manifest.find("run_arbitrary_sql")).isEmpty();
        assertThat(manifest.find("fineract_report_run")).isEmpty(); // Not in v1 manifest on purpose.
    }

    @Test
    void moneyMovingToolsAreClassifiedAsWrites() {
        assertThat(manifest.find("fineract_loan_approve").orElseThrow().write()).isTrue();
        assertThat(manifest.find("fineract_loan_repayment").orElseThrow().write()).isTrue();
        assertThat(manifest.find("fineract_client_details").orElseThrow().write()).isFalse();
    }

    @Test
    void clientDetailsRedactsPiiForCloudMode() {
        assertThat(manifest.find("fineract_client_details").orElseThrow().redactFields())
                .contains("mobileNo", "dateOfBirth");
    }

    @Test
    void openAiSchemasCarryNamesAndRequiredParams() {
        Map<String, Object> schema = manifest.find("fineract_loan_approve").orElseThrow().toOpenAiSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) schema.get("function");
        assertThat(function.get("name")).isEqualTo("fineract_loan_approve");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        assertThat((Iterable<String>) parameters.get("required")).contains("loanId");
    }

    @Test
    void humanSummaryInterpolatesArguments() {
        ToolDefinition tool = manifest.find("fineract_loan_repayment").orElseThrow();
        assertThat(tool.humanSummary(Map.of("loanId", 42, "transactionAmount", 5000)))
                .isEqualTo("Record repayment of 5000 on loan #42");
    }
}
