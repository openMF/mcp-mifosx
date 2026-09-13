/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Whether a value is one the officer could have typed into the web app.
 *
 * <p>Every rule checked here is copied from a validator the Mifos X web app already applies to
 * the same field. That is the whole design: the Copilot must not refuse work an officer can do
 * in the form, and must not accept work the form would refuse, because otherwise it is a way
 * round the app's own controls rather than a companion to it.
 */
class ArgumentCheckTest {

    /** The real manifest, so these tests fail if somebody removes a rule from it. */
    private static final ToolManifest MANIFEST = ToolManifest.load(
            ArgumentCheckTest.class.getResourceAsStream("/tools.yaml"));

    private static ToolDefinition tool(String name) {
        return MANIFEST.find(name).orElseThrow();
    }

    /**
     * The one a reviewer found by typing it in.
     *
     * <p>client-general-step.component.ts puts Validators.pattern('(^[A-Za-z]).*') on firstname,
     * so the form will not submit this. The Copilot created the client anyway, because it had
     * no opinion about what a name looks like and Fineract does not check.
     */
    @Test
    void fifteenDigitsIsNotAName() {
        List<String> problems = ArgumentCheck.problems(tool("mifos_client_create"),
                Map.of("firstname", "999999999999999", "lastname", "Wanjiru", "activationDate", "2026-08-23"));

        assertThat(problems).isNotEmpty();
        assertThat(problems.get(0)).as("said in words an officer can act on")
                .isEqualTo("First name must start with a letter.");
    }

    @Test
    void anOrdinaryNameIsFine() {
        assertThat(ArgumentCheck.problems(tool("mifos_client_create"),
                Map.of("firstname", "Grace", "lastname", "Wanjiru", "activationDate", "2026-08-23"))).isEmpty();
    }

    /**
     * The form only constrains the first character, and so does this.
     *
     * <p>Being stricter would be its own bug. Names carry apostrophes, hyphens, accents and
     * numerals, and an officer who can enter O'Brien-Smith II in the form must be able to ask
     * for it in a sentence.
     */
    @Test
    void aNameTheFormWouldAcceptIsNotRefusedForBeingUnusual() {
        for (String name : List.of("O'Brien-Smith", "Ngozi Adichie II", "Zoë", "MacDonald 3rd")) {
            assertThat(ArgumentCheck.problems(tool("mifos_client_create"),
                    Map.of("firstname", name, "lastname", "Wanjiru", "activationDate", "2026-08-23")))
                    .as("the web app would accept %s", name)
                    .isEmpty();
        }
    }

    @Test
    void aNegativeRepaymentIsRefused() {
        List<String> problems = ArgumentCheck.problems(tool("mifos_loan_repayment"),
                Map.of("loanId", 1, "transactionAmount", -500, "transactionDate", "2026-08-23"));

        assertThat(problems).isNotEmpty();
        assertThat(problems.get(0)).contains("Repayment amount", "not a negative one");
    }

    /** The web app blocks a zero repayment with Validators.min(0.001), and so does this. */
    @Test
    void aZeroRepaymentIsRefused() {
        assertThat(ArgumentCheck.problems(tool("mifos_loan_repayment"),
                Map.of("loanId", 1, "transactionAmount", 0, "transactionDate", "2026-08-23")))
                .isNotEmpty();
    }

    @Test
    void anOrdinaryRepaymentIsFine() {
        assertThat(ArgumentCheck.problems(tool("mifos_loan_repayment"),
                Map.of("loanId", 1, "transactionAmount", 2500.50, "transactionDate", "2026-08-23"))).isEmpty();
    }

    /**
     * A number the model wrote in scientific notation is the same number.
     *
     * <p>Jackson will hand back 1.2E7 for a large enough double, and refusing that as "not an
     * amount" would be the Copilot arguing with itself about notation rather than value.
     */
    @Test
    void anAmountInScientificNotationIsStillAnAmount() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("clientId", 1);
        args.put("productId", 1);
        args.put("principal", 1.2E7);
        args.put("numberOfRepayments", 12);
        args.put("submittedOnDate", "2026-08-23");
        args.put("expectedDisbursementDate", "2026-09-15");

        assertThat(ArgumentCheck.problems(tool("mifos_loan_create"), args)).isEmpty();
    }

    @Test
    void aLoanOfZeroInstalmentsIsRefused() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("clientId", 1);
        args.put("productId", 1);
        args.put("principal", 12000);
        args.put("numberOfRepayments", 0);
        args.put("submittedOnDate", "2026-08-23");
        args.put("expectedDisbursementDate", "2026-09-15");

        assertThat(ArgumentCheck.problems(tool("mifos_loan_create"), args))
                .anyMatch((problem) -> problem.contains("Repayments"));
    }

    /**
     * Declaring a bound is declaring the field numeric.
     *
     * <p>A value that is not a number cannot be compared to a minimum, and letting it through
     * on those grounds meant a card was raised for a loan of "none" repayments. The officer
     * found out it was nonsense when Fineract said so, which is the thing this whole change
     * is meant to stop happening.
     */
    @Test
    void aWordWhereANumberBelongsIsRefusedRatherThanSkipped() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("clientId", 1);
        args.put("productId", 1);
        args.put("principal", 12000);
        args.put("numberOfRepayments", "none");
        args.put("submittedOnDate", "2026-08-23");
        args.put("expectedDisbursementDate", "2026-09-15");

        assertThat(ArgumentCheck.problems(tool("mifos_loan_create"), args))
                .contains("Repayments must be a number.");
    }

    /** A field with no bound declared is not turned into a number by this. */
    @Test
    void aFieldWithNoBoundIsNotForcedToBeNumeric() {
        assertThat(ArgumentCheck.problems(tool("mifos_client_create"),
                Map.of("firstname", "Grace", "lastname", "Wanjiru", "activationDate", "2026-08-23",
                        "mobileNo", "+254 712 345678"))).isEmpty();
    }

    @Test
    void aMissingRequiredValueIsNamedByItsLabelNotItsFieldName() {
        List<String> problems = ArgumentCheck.problems(tool("mifos_client_create"),
                Map.of("lastname", "Wanjiru", "activationDate", "2026-08-23"));

        assertThat(problems).contains("First name is needed.");
    }

    @Test
    void anOptionalValueNobodySetIsNotAProblem() {
        assertThat(ArgumentCheck.problems(tool("mifos_client_create"),
                Map.of("firstname", "Grace", "lastname", "Wanjiru", "activationDate", "2026-08-23")))
                .as("mobileNo, dateOfBirth, externalId and officeId are all optional")
                .isEmpty();
    }

    /**
     * The length limit is Fineract's column, not the form's rule.
     *
     * <p>The web app puts no maxlength on a name at all. Fineract stores it in a varchar(50).
     * Without this the officer confirmed a card and then got a database complaint back, which
     * is a poor way to learn that a name was two characters too long.
     */
    @Test
    void aNameLongerThanTheColumnIsRefusedBeforeTheCardNotAfterIt() {
        List<String> problems = ArgumentCheck.problems(tool("mifos_client_create"),
                Map.of("firstname", "M".repeat(51), "lastname", "Kimani", "activationDate", "2026-08-23"));

        assertThat(problems).contains("First name must be 50 characters or fewer.");
    }

    @Test
    void aNameExactlyTheLengthOfTheColumnIsFine() {
        assertThat(ArgumentCheck.problems(tool("mifos_client_create"),
                Map.of("firstname", "M".repeat(50), "lastname", "Kimani", "activationDate", "2026-08-23"))).isEmpty();
    }

    /**
     * One place the form is looser than the server, and the server wins.
     *
     * <p>The web app allows Validators.min(0) on the repayment count. Fineract requires
     * strictly more than zero. Copying the form here would let the Copilot submit something
     * the server always rejects, which helps nobody.
     */
    @Test
    void aLoanOfNoInstalmentsIsRefusedEvenThoughTheFormWouldAllowIt() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("clientId", 1);
        args.put("productId", 1);
        args.put("principal", 12000);
        args.put("numberOfRepayments", 0);
        args.put("submittedOnDate", "2026-08-23");
        args.put("expectedDisbursementDate", "2026-09-15");

        assertThat(ArgumentCheck.problems(tool("mifos_loan_create"), args))
                .contains("Repayments must be at least 1.");
    }

    @Test
    void aLoanOfNothingIsRefused() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("clientId", 1);
        args.put("productId", 1);
        args.put("principal", 0);
        args.put("numberOfRepayments", 12);
        args.put("submittedOnDate", "2026-08-23");
        args.put("expectedDisbursementDate", "2026-09-15");

        assertThat(ArgumentCheck.problems(tool("mifos_loan_create"), args))
                .anyMatch((problem) -> problem.startsWith("Principal"));
    }

    /**
     * A rule the manifest gets wrong must not take the tool out of service.
     *
     * <p>A pattern that will not compile is a mistake in the manifest, and refusing every value
     * because of it would stop an officer working over a typo nobody has noticed yet.
     */
    @Test
    void aBrokenPatternInTheManifestDoesNotBlockTheOfficer() {
        ToolManifest broken = ToolManifest.load(new ByteArrayInputStream("""
                tools:
                  - name: t
                    description: d
                    write: true
                    params:
                      - { name: firstname, type: string, required: true, label: First name, pattern: '([unclosed' }
                    rest: { method: POST, path: "/x" }
                """.getBytes(StandardCharsets.UTF_8)));

        assertThat(ArgumentCheck.problems(broken.find("t").orElseThrow(), Map.of("firstname", "Grace"))).isEmpty();
    }
}
