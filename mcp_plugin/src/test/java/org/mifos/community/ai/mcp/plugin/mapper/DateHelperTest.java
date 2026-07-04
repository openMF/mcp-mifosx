// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DateHelper}.
 */
class DateHelperTest {

    @Test
    @DisplayName("currentDateFormatted returns today in 'dd MMMM yyyy' format")
    void currentDateFormatted_returnsTodayInDefaultFormat() {
        String result = DateHelper.currentDateFormatted();

        // Verify it matches the expected format
        assertNotNull(result);
        assertFalse(result.isBlank());

        // Parse it back to verify the format is valid
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);
        LocalDate parsed = LocalDate.parse(result, formatter);
        assertEquals(LocalDate.now(), parsed);
    }

    @Test
    @DisplayName("currentDateIso returns today in 'yyyy-MM-dd' format")
    void currentDateIso_returnsTodayInIsoFormat() {
        String result = DateHelper.currentDateIso();

        assertNotNull(result);
        LocalDate parsed = LocalDate.parse(result); // ISO format parses by default
        assertEquals(LocalDate.now(), parsed);
    }

    @Test
    @DisplayName("dateOrToday returns provided date when non-null")
    void dateOrToday_returnsProvidedDate() {
        String provided = "15 June 2025";
        assertEquals(provided, DateHelper.dateOrToday(provided));
    }

    @Test
    @DisplayName("dateOrToday returns current date when null")
    void dateOrToday_returnsTodayWhenNull() {
        String result = DateHelper.dateOrToday(null);
        assertEquals(DateHelper.currentDateFormatted(), result);
    }

    @Test
    @DisplayName("dateOrToday returns current date when blank")
    void dateOrToday_returnsTodayWhenBlank() {
        String result = DateHelper.dateOrToday("   ");
        assertEquals(DateHelper.currentDateFormatted(), result);
    }

    @Test
    @DisplayName("dateOrTodayIso returns provided date when non-null")
    void dateOrTodayIso_returnsProvidedDate() {
        String provided = "2025-06-15";
        assertEquals(provided, DateHelper.dateOrTodayIso(provided));
    }

    @Test
    @DisplayName("dateOrTodayIso returns ISO date when null")
    void dateOrTodayIso_returnsTodayIsoWhenNull() {
        String result = DateHelper.dateOrTodayIso(null);
        assertEquals(DateHelper.currentDateIso(), result);
    }

    @Test
    @DisplayName("LOCALE_STRING is 'en'")
    void localeString_isEnglish() {
        assertEquals("en", DateHelper.LOCALE_STRING);
    }

    @Test
    @DisplayName("DEFAULT_DATE_FORMAT is 'dd MMMM yyyy'")
    void defaultDateFormat_matchesExpected() {
        assertEquals("dd MMMM yyyy", DateHelper.DEFAULT_DATE_FORMAT);
    }
}
