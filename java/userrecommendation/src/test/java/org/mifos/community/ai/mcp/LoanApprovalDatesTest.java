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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * approveLoanAccount used to require the approval date to be after the template's approvalDate,
 * which Fineract sets to today, so every approval date was rejected: past ones by the tool and
 * future ones by Fineract.
 */
class LoanApprovalDatesTest {

    // What Fineract's approval template gives as approvalDate: today's business date.
    private static final String TODAY = "12 November 2026";

    @Test
    void usesTodayForBothDatesWhenNoneAreGiven() {
        LoanApprovalDates.Dates dates = LoanApprovalDates.resolve(null, null, TODAY);
        assertEquals(TODAY, dates.approvedOnDate());
        assertEquals(TODAY, dates.expectedDisbursementDate());
    }

    @Test
    void acceptsAnApprovalDateInThePast() {
        LoanApprovalDates.Dates dates = LoanApprovalDates.resolve("10 November 2026", null, TODAY);
        assertEquals("10 November 2026", dates.approvedOnDate());
        assertEquals(TODAY, dates.expectedDisbursementDate());
    }

    @Test
    void acceptsAnApprovalDateOfToday() {
        assertEquals(TODAY, LoanApprovalDates.resolve(TODAY, null, TODAY).approvedOnDate());
    }

    @Test
    void acceptsDisbursementOnTheApprovalDate() {
        LoanApprovalDates.Dates dates = LoanApprovalDates.resolve("10 November 2026", "10 November 2026", TODAY);
        assertEquals("10 November 2026", dates.expectedDisbursementDate());
    }

    @Test
    void checksDisbursementAgainstTheChosenApprovalDateNotTheTemplate() {
        // Before today, but after the approval date, so it is fine.
        LoanApprovalDates.Dates dates = LoanApprovalDates.resolve("05 November 2026", "08 November 2026", TODAY);
        assertEquals("08 November 2026", dates.expectedDisbursementDate());
    }

    @Test
    void rejectsDisbursementBeforeTheApprovalDate() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoanApprovalDates.resolve("10 November 2026", "09 November 2026", TODAY));
        assertTrue(e.getMessage().contains("09 November 2026"), e.getMessage());
        assertTrue(e.getMessage().contains("10 November 2026"), e.getMessage());
    }

    @Test
    void leavesAFutureApprovalDateForFineractToReject() {
        // Fineract gives the clear "cannot be in the future" error, so the disbursement default
        // must not be earlier than the approval date and trip the check above instead.
        LoanApprovalDates.Dates dates = LoanApprovalDates.resolve("20 November 2026", null, TODAY);
        assertEquals("20 November 2026", dates.approvedOnDate());
        assertEquals("20 November 2026", dates.expectedDisbursementDate());
    }

    @Test
    void acceptsShortMonthNamesAndSendsTheFullName() {
        LoanApprovalDates.Dates dates = LoanApprovalDates.resolve("29 Sep 2026", "30 sep 2026", TODAY);
        assertEquals("29 September 2026", dates.approvedOnDate());
        assertEquals("30 September 2026", dates.expectedDisbursementDate());
    }

    @Test
    void acceptsASingleDigitDay() {
        assertEquals("05 November 2026", LoanApprovalDates.resolve("5 November 2026", null, TODAY).approvedOnDate());
    }

    @Test
    void rejectsADayThatTheMonthDoesNotHave() {
        // Without strict parsing this became 30 April and was sent to Fineract unnoticed.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoanApprovalDates.resolve("31 April 2026", null, TODAY));
        assertTrue(e.getMessage().contains("31 April 2026"), e.getMessage());
    }

    @Test
    void rejectsTheTwentyNinthOfFebruaryOutsideALeapYear() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoanApprovalDates.resolve("29 February 2026", "02 March 2026", TODAY));
        assertTrue(e.getMessage().contains("29 February 2026"), e.getMessage());
    }

    @Test
    void acceptsTheTwentyNinthOfFebruaryInALeapYear() {
        assertEquals("29 February 2028", LoanApprovalDates.resolve(null, "29 Feb 2028", TODAY).expectedDisbursementDate());
    }

    @Test
    void explainsTheFormatWhenADateCannotBeRead() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoanApprovalDates.resolve("2026-11-10", null, TODAY));
        assertTrue(e.getMessage().contains("approvalDate"), e.getMessage());
        assertTrue(e.getMessage().contains("29 May 2025"), e.getMessage());
    }
}
