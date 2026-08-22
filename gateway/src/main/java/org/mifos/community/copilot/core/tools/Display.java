/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How values are written for the person approving them.
 *
 * <p>A confirmation card is the last thing an officer reads before money moves, so it has to
 * read like a banking screen and not like a database row: amounts carry their currency and
 * grouped thousands, dates are spelled out, and nothing is shown as a bare identifier.
 */
public final class Display {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /**
     * Reserved key under which enrichment reports the currency of the record being changed.
     * The leading '#' cannot collide with a manifest label, and the card strips it before
     * rendering: it decides how amounts are written rather than being shown as a row.
     */
    public static final String CURRENCY = "#currency";

    private Display() {}

    /** Grouped thousands and two decimals, prefixed with the account's currency where known. */
    public static String money(double amount, String currency) {
        String number = String.format(Locale.US, "%,.2f", amount);
        return currency == null || currency.isBlank() ? number : currency + " " + number;
    }

    /**
     * "21 August 2026" rather than "2026-08-21". {@code today} is resolved against the tenant's
     * configured business date, which is not necessarily the gateway host's calendar day.
     */
    public static String date(String value, String businessDate) {
        String iso = "today".equalsIgnoreCase(value) ? businessDate : value;
        try {
            return LocalDate.parse(iso).format(DAY);
        } catch (RuntimeException e) {
            return value; // Show what was actually supplied rather than swallowing it.
        }
    }

    /** Rows whose key is reserved for the card machinery rather than shown to the officer. */
    public static boolean isReserved(String label) {
        return label != null && label.startsWith("#");
    }
}
