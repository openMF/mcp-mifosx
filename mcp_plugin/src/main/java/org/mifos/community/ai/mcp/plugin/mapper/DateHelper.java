// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.mapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Date formatting utilities for MCP tool arguments and responses.
 * <p>
 * Provides consistent date handling matching the existing java/backoffice
 * implementation behavior:
 * <ul>
 *   <li>Default format: {@code dd MMMM yyyy} (e.g., "03 July 2025")</li>
 *   <li>ISO format: {@code yyyy-MM-dd} (used for client creation)</li>
 *   <li>Locale: {@code en}</li>
 *   <li>Fallback: current date when no date is provided</li>
 * </ul>
 */
public final class DateHelper {

    /** Default date format matching existing MCP tool behavior */
    public static final String DEFAULT_DATE_FORMAT = "dd MMMM yyyy";

    /** ISO date format used for client creation and submission dates */
    public static final String ISO_DATE_FORMAT = "yyyy-MM-dd";

    /** Default locale for date formatting */
    public static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    /** Default locale string sent in Fineract payloads */
    public static final String LOCALE_STRING = "en";

    private static final DateTimeFormatter DEFAULT_FORMATTER =
            DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT, DEFAULT_LOCALE);

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern(ISO_DATE_FORMAT, DEFAULT_LOCALE);

    private DateHelper() {
        // Utility class — no instantiation
    }

    /**
     * Get the current date formatted in the default pattern ({@code dd MMMM yyyy}).
     *
     * @return today's date as a formatted string
     */
    public static String currentDateFormatted() {
        return LocalDate.now().format(DEFAULT_FORMATTER);
    }

    /**
     * Get the current date formatted in ISO pattern ({@code yyyy-MM-dd}).
     *
     * @return today's date as an ISO-formatted string
     */
    public static String currentDateIso() {
        return LocalDate.now().format(ISO_FORMATTER);
    }

    /**
     * Return the provided date if non-null/non-blank, otherwise return today's date.
     * Uses the default format ({@code dd MMMM yyyy}).
     *
     * @param providedDate the date string from the MCP tool argument (may be null)
     * @return the provided date or today's date as fallback
     */
    public static String dateOrToday(String providedDate) {
        if (providedDate != null && !providedDate.isBlank()) {
            return providedDate;
        }
        return currentDateFormatted();
    }

    /**
     * Return the provided date if non-null/non-blank, otherwise return today's date
     * in ISO format ({@code yyyy-MM-dd}).
     *
     * @param providedDate the date string from the MCP tool argument (may be null)
     * @return the provided date or today's ISO date as fallback
     */
    public static String dateOrTodayIso(String providedDate) {
        if (providedDate != null && !providedDate.isBlank()) {
            return providedDate;
        }
        return currentDateIso();
    }
}
