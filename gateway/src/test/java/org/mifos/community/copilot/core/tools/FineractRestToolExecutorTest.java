/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for body templating. The critical invariant: EVERY argument the officer
 * saw on the approval card reaches Fineract. A card/execution mismatch on a money field is
 * the exact failure this gateway exists to prevent.
 */
class FineractRestToolExecutorTest {

    private static final String APPROVE_TEMPLATE =
            "{\"approvedOnDate\":\"${approvedOnDate}\",\"approvedLoanAmount\":\"${approvedLoanAmount}\","
                    + "\"locale\":\"en\",\"dateFormat\":\"dd MMMM yyyy\"}";

    private final FineractRestToolExecutor executor = new FineractRestToolExecutor("https://example.org");
    /** A fixed stand-in for the core banking business date. */
    private static final String TODAY = "2026-08-09";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void approvedAmountShownOnTheCardReachesTheBody() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("approvedOnDate", executor.normalizeValue("approvedOnDate", "2026-08-09", TODAY, false));
        args.put("approvedLoanAmount", 40000);

        JsonNode body = mapper.readTree(executor.buildBody(APPROVE_TEMPLATE, args, TODAY));

        assertThat(body.get("approvedLoanAmount").asInt()).isEqualTo(40000); // Number stays unquoted.
        assertThat(body.get("approvedOnDate").asText()).isEqualTo("09 August 2026"); // Fineract format.
        assertThat(body.get("locale").asText()).isEqualTo("en");
    }

    @Test
    void omittedOptionalFieldIsRemovedNotSentAsEmptyString() throws Exception {
        JsonNode body = mapper.readTree(executor.buildBody(APPROVE_TEMPLATE, Map.of("approvedOnDate", "today"), TODAY));

        assertThat(body.has("approvedLoanAmount")).isFalse(); // Stripped, never "".
        assertThat(body.get("approvedOnDate").asText()).isNotBlank();
    }

    @Test
    void stringValuesCannotRewriteJsonStructure() throws Exception {
        // A malicious/hallucinated arg carrying quotes and braces must stay a string value.
        String hostile = "x\",\"approvedLoanAmount\":999999,\"y\":\"z";
        JsonNode body = mapper.readTree(
                executor.buildBody("{\"note\":\"${note}\"}", Map.of("note", hostile), TODAY));

        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get("note").asText()).isEqualTo(hostile);
        assertThat(body.has("approvedLoanAmount")).isFalse();
    }

    @Test
    void tokenLikeValuesAreNotRecursivelySubstituted() throws Exception {
        JsonNode body = mapper.readTree(
                executor.buildBody("{\"note\":\"${note}\"}", Map.of("note", "${approvedOnDate}"), TODAY));

        // The value must be treated as data, never re-expanded as a template token...
        // (it IS stripped-or-kept as a literal, not resolved against other args).
        assertThat(body.toString()).doesNotContain("August");
    }

    @Test
    void aFutureDateIsPulledBackToTheBusinessDate() {
        // Fineract refuses to book an approval in its own future, so an approval date ahead
        // of the business date is pulled back rather than sent and rejected.
        assertThat(executor.normalizeValue("approvedOnDate", "2026-12-31", TODAY, false))
                .isEqualTo("09 August 2026");
    }

    @Test
    void aDateThatIsMeantToBeAheadIsLeftWhereTheOfficerPutIt() {
        // An expected disbursement is a plan. Pulling it back to today rewrites every
        // instalment date on the schedule Fineract generates from it.
        assertThat(executor.normalizeValue("expectedDisbursementDate", "2026-12-31", TODAY, true))
                .isEqualTo("31 December 2026");
    }

    @Test
    void theWordTodayBecomesTheBusinessDateNotTheHostClock() {
        assertThat(executor.normalizeValue("approvedOnDate", "today", TODAY, false))
                .isEqualTo("09 August 2026");
    }

    @Test
    void aValueThatLooksLikeASlotIsStoredAndNotResolvedAgain() throws Exception {
        // Repeated string substitution wrote the value in, then read it back as a slot on a
        // later pass, so a client whose surname was literally ${mobileNo} was persisted with
        // their phone number as their surname.
        String template = "{\"lastname\":\"${lastname}\",\"mobileNo\":\"${mobileNo}\"}";
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("lastname", "${mobileNo}");
        args.put("mobileNo", "0803 555 0147");

        JsonNode body = mapper.readTree(executor.buildBody(template, args, TODAY));

        assertThat(body.get("lastname").asText()).isEqualTo("${mobileNo}");
        assertThat(body.get("mobileNo").asText()).isEqualTo("0803 555 0147");
    }

    @Test
    void aValueThatLooksLikeASlotDoesNotDeleteItsOwnField() throws Exception {
        String template = "{\"lastname\":\"${lastname}\",\"firstname\":\"${firstname}\"}";
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("lastname", "${firstname}");
        args.put("firstname", "Aisha");

        JsonNode body = mapper.readTree(executor.buildBody(template, args, TODAY));

        assertThat(body.has("lastname")).isTrue();
        assertThat(body.get("lastname").asText()).isEqualTo("${firstname}");
    }

    @Test
    void aValueCarryingQuotesAndBracesStaysAValue() throws Exception {
        String template = "{\"firstname\":\"${firstname}\",\"legalFormId\":1}";

        JsonNode body = mapper.readTree(executor.buildBody(template,
                Map.of("firstname", "\",\"legalFormId\":99,\"x\":\""), TODAY));

        assertThat(body.get("legalFormId").asInt()).isEqualTo(1);
        assertThat(body.size()).isEqualTo(2);
    }
}
