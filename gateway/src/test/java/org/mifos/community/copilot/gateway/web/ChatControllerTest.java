/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.gateway.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deployment-drift guard: the Copilot must never execute against a different Fineract
 * than the one on the officer's screen.
 */
class ChatControllerTest {

    @Test
    void sameOriginMatchesRegardlessOfPathAndCase() {
        assertThat(ChatController.originsMatch(
                "https://sandbox.mifos.community",
                "https://SANDBOX.mifos.community/fineract-provider")).isTrue();
    }

    @Test
    void differentHostIsAMismatch() {
        assertThat(ChatController.originsMatch(
                "https://demo.mifos.community",
                "https://sandbox.mifos.community")).isFalse();
    }

    @Test
    void differentSchemeOrPortIsAMismatch() {
        assertThat(ChatController.originsMatch("http://bank.example", "https://bank.example")).isFalse();
        assertThat(ChatController.originsMatch("https://bank.example:8443", "https://bank.example")).isFalse();
    }

    @Test
    void malformedInputFailsClosed() {
        assertThat(ChatController.originsMatch("not a url", "https://bank.example")).isFalse();
    }
}
