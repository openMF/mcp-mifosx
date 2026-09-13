/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mifos.community.copilot.core.tools.Display;

/** How values read on the card an officer approves. */
class DisplayTest {

    @Test
    void amountsCarryTheirCurrencyAndGroupedThousands() {
        assertThat(Display.money(28000, "USD")).isEqualTo("USD 28,000.00");
        assertThat(Display.money(1234567.5, "KES")).isEqualTo("KES 1,234,567.50");
        assertThat(Display.money(500, "")).isEqualTo("500.00");
    }

    @Test
    void datesAreSpelledOut() {
        assertThat(Display.date("2026-08-21", "2026-08-21")).isEqualTo("21 August 2026");
    }

    @Test
    void todayResolvesToTheTenantsBusinessDateNotTheHostClock() {
        // The gateway may be an hour ahead of the tenant; the officer must see the day the
        // write will actually be booked on.
        assertThat(Display.date("today", "2026-08-21")).isEqualTo("21 August 2026");
    }

    @Test
    void anUnparseableValueIsShownRatherThanSwallowed() {
        assertThat(Display.date("next friday", "2026-08-21")).isEqualTo("next friday");
    }

    @Test
    void reservedRowsAreForTheCardMachineryAndNeverShownToTheOfficer() {
        assertThat(Display.isReserved(Display.CURRENCY)).isTrue();
        assertThat(Display.isReserved("Client")).isFalse();
        assertThat(Display.isReserved("Approved amount")).isFalse();
        assertThat(Display.isReserved(null)).isFalse();
    }
}
