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

/** The shipped tools.yaml is the enforcement authority, so validate its actual content. */
class ToolManifestTest {

    private final ToolManifest manifest = ToolManifest.load(getClass().getResourceAsStream("/tools.yaml"));

    @Test
    void loadsTheShippedManifest() {
        assertThat(manifest.size()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void unknownToolsResolveEmpty_defaultDeny() {
        assertThat(manifest.find("run_arbitrary_sql")).isEmpty();
        assertThat(manifest.find("mifos_report_run")).isEmpty(); // Not in v1 manifest on purpose.
    }

    @Test
    void moneyMovingToolsAreClassifiedAsWrites() {
        assertThat(manifest.find("mifos_loan_approve").orElseThrow().write()).isTrue();
        assertThat(manifest.find("mifos_loan_repayment").orElseThrow().write()).isTrue();
        assertThat(manifest.find("mifos_client_details").orElseThrow().write()).isFalse();
    }

    @Test
    void clientDetailsRedactsPiiForCloudMode() {
        assertThat(manifest.find("mifos_client_details").orElseThrow().redactFields())
                .contains("mobileNo", "dateOfBirth");
    }

    @Test
    void openAiSchemasCarryNamesAndRequiredParams() {
        Map<String, Object> schema = manifest.find("mifos_loan_approve").orElseThrow().toOpenAiSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) schema.get("function");
        assertThat(function.get("name")).isEqualTo("mifos_loan_approve");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        assertThat((Iterable<String>) parameters.get("required")).contains("loanId");
    }

    @Test
    void humanSummaryInterpolatesArguments() {
        ToolDefinition tool = manifest.find("mifos_loan_repayment").orElseThrow();
        assertThat(tool.humanSummary(Map.of("productName", "Weekly Loan", "clientName", "Aisha Bello")))
                .isEqualTo("Record repayment on Weekly Loan for Aisha Bello");
    }

    @Test
    void noToolNameLeaksTheBackendProductName() {
        // Officers see tool names in the activity trail; Fineract is our implementation
        // detail, not their vocabulary.
        assertThat(manifest.all()).noneMatch((tool) -> tool.name().contains("fineract"));
    }

    @Test
    void everyParameterOfferedToTheModelIsLabelledForTheOfficer() {
        for (ToolDefinition tool : manifest.all()) {
            for (ToolDefinition.Param param : tool.params()) {
                assertThat(param.displayLabel())
                        .as("label for %s.%s", tool.name(), param.name())
                        .isNotBlank();
            }
        }
    }

    @Test
    void identifiersAreHiddenFromCardsAndReplacedByAnEnrichedName() {
        // "Loan account 12" tells an officer nothing, so every write that takes an id must
        // read the account first and show the client, account number and product instead.
        for (ToolDefinition tool : manifest.all()) {
            boolean takesAnId = tool.params().stream()
                    .anyMatch((param) -> param.name().endsWith("Id") && !param.show());
            if (takesAnId) {
                assertThat(tool.enrich())
                        .as("enrichment for %s", tool.name())
                        .isNotEmpty();
                assertThat(tool.enrich()).allSatisfy((block) -> {
                    assertThat(block.path()).isNotBlank();
                    assertThat(block.fields()).isNotEmpty();
                });
            }
        }
    }

    @Test
    void amountsAndDatesAreMarkedForFormatting() {
        ToolDefinition approve = manifest.find("mifos_loan_approve").orElseThrow();
        assertThat(param(approve, "approvedLoanAmount").isMoney()).isTrue();
        assertThat(param(approve, "approvedLoanAmount").displayLabel()).isEqualTo("Approved amount");
        assertThat(param(approve, "approvedOnDate").isDate()).isTrue();
        assertThat(param(approve, "loanId").show()).isFalse();
    }

    private static ToolDefinition.Param param(ToolDefinition tool, String name) {
        return tool.params().stream().filter((p) -> p.name().equals(name)).findFirst().orElseThrow();
    }
}
