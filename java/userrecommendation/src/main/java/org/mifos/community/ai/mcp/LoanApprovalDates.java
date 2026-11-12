/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.mifos.community.ai.mcp;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/**
 * Works out the dates sent when approving a loan.
 *
 * Fineract's approval template gives today's business date as its approvalDate, not the
 * date the loan was submitted, so it is only used as the default. Fineract itself checks
 * the approval date against the submitted date and rejects dates in the future.
 */
final class LoanApprovalDates {

    /** The format sent to Fineract (dateFormat "dd MMMM yyyy", locale "en"). */
    static final DateTimeFormatter FINERACT_FORMAT = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    /**
     * Accepts "29 May 2025", "29 September 2025" and "29 Sep 2025", in any letter case.
     * STRICT rejects dates that don't exist, like "31 April 2026", instead of moving them to
     * the 30th; it needs uuuu (year) rather than yyyy (year of era).
     */
    private static final DateTimeFormatter INPUT_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("[d MMMM uuuu][d MMM uuuu]")
            .toFormatter(Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT);

    record Dates(String approvedOnDate, String expectedDisbursementDate) {
    }

    private LoanApprovalDates() {
    }

    /**
     * @param approvalDate             the approval date asked for, or null for the template date
     * @param expectedDisbursementDate the disbursement date asked for, or null for the later of today and the approval date
     * @param templateDate             the approvalDate from Fineract's approval template ("dd MMMM yyyy")
     */
    static Dates resolve(String approvalDate, String expectedDisbursementDate, String templateDate) {
        LocalDate today = parse(templateDate, "template approvalDate");
        LocalDate approvedOn = approvalDate != null ? parse(approvalDate, "approvalDate") : today;

        // Without a disbursement date, use today, or the approval date if that is later.
        LocalDate expected = expectedDisbursementDate != null
                ? parse(expectedDisbursementDate, "expectedDisbursementDate")
                : (approvedOn.isAfter(today) ? approvedOn : today);

        if (expected.isBefore(approvedOn)) {
            throw new IllegalArgumentException("The expected disbursement date (" + expected.format(FINERACT_FORMAT)
                    + ") cannot be earlier than the approval date (" + approvedOn.format(FINERACT_FORMAT) + ").");
        }
        return new Dates(approvedOn.format(FINERACT_FORMAT), expected.format(FINERACT_FORMAT));
    }

    private static LocalDate parse(String value, String name) {
        try {
            return LocalDate.parse(value.trim(), INPUT_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must look like \"29 May 2025\", but was \"" + value + "\".", e);
        }
    }
}
