/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.ai.decision.integration;

import org.community.mifos.ai.decision.dto.DocumentEvaluationRequest;
import org.community.mifos.ai.decision.dto.DocumentEvaluationResponse;
import org.community.mifos.ai.decision.service.BankStatementClassifierService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Live integration test against a real Ollama instance running the "clef" model.
 *
 * To run:
 *   1. ollama pull clef
 *   2. export RUN_LIVE_OLLAMA=true
 *   3. mvn test -Dtest=BankStatementClassifierLiveIT
 *
 * Skipped automatically when the environment variable is not set.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_OLLAMA", matches = "true")
class BankStatementClassifierLiveIT {

    @Autowired
    private BankStatementClassifierService classifierService;

    @Test
    @DisplayName("Live: clear bank statement text is recognised")
    void liveBankStatementIsRecognised() {
        Assumptions.assumeTrue(classifierService != null);

        String sampleStatement = """
                FIRST NATIONAL BANK
                Account Statement
                Account Holder: Maria Gonzalez
                Account Number: 9876543210
                Statement Period: 01 March 2026 – 31 March 2026
                Opening Balance: USD 5,420.33
                Closing Balance: USD 6,180.12
                
                Date        Description                     Debit      Credit     Balance
                03-Mar-26   Direct Deposit - ACME Corp                 3,200.00   8,620.33
                05-Mar-26   Grocery Store #4421            87.45                  8,532.88
                12-Mar-26   Online Transfer to Savings     500.00                  8,032.88
                28-Mar-26   Mortgage Payment             1,852.76                  6,180.12
                
                End of Statement
                """;

        DocumentEvaluationResponse result = classifierService.evaluate(
                new DocumentEvaluationRequest(sampleStatement, "march_2026_statement.pdf", "LOAN-4421")
        );

        System.out.println("Live decision: " + result);

        // We expect a high probability; exact value depends on the model version
        org.assertj.core.api.Assertions.assertThat(result.probability())
                .as("Probability that the sample is a bank statement")
                .isGreaterThan(0.6);
        org.assertj.core.api.Assertions.assertThat(result.isBankAccountStatement()).isTrue();
    }

    @Test
    @DisplayName("Live: payslip is rejected as bank statement")
    void livePayslipIsRejected() {
        String payslip = """
                ACME CORPORATION
                PAY ADVICE
                Employee Name: Carlos Rivera
                Employee ID: EMP-7788
                Pay Period: 01-15 March 2026
                Gross Earnings: $4,850.00
                Tax Withheld: $1,120.00
                Net Pay: $3,730.00
                """;

        DocumentEvaluationResponse result = classifierService.evaluate(
                new DocumentEvaluationRequest(payslip)
        );

        System.out.println("Live payslip decision: " + result);

        org.assertj.core.api.Assertions.assertThat(result.isBankAccountStatement()).isFalse();
    }
}
