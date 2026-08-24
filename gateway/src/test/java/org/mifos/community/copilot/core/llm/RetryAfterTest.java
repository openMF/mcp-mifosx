/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Telling an officer they are being throttled, rather than that the model is broken.
 *
 * <p>Both used to produce the same sentence. A wait the officer had caused themselves by
 * asking two questions in quick succession read as the service being down, which is a poor
 * trade: they either give up on something that would have worked in twenty seconds, or they
 * keep pressing and make it worse. A number turns it from something to stare at into
 * something to wait out.
 */
class RetryAfterTest {

    @Test
    void aThrottledCallCarriesHowLongToWait() {
        LlmException throttled = new LlmException("rate limit", null, true, false, 23);

        assertThat(throttled.isRateLimited()).isTrue();
        assertThat(throttled.isClientError()).isFalse();
        assertThat(throttled.retryAfterSeconds()).isEqualTo(23);
    }

    @Test
    void aProviderThatDidNotSayLeavesItAtZero() {
        assertThat(new LlmException("rate limit", null, true).retryAfterSeconds()).isZero();
    }

    /** A refusal is a settled fact, so there is nothing to wait for. */
    @Test
    void aRefusalIsNeitherThrottledNorWorthRetrying() {
        LlmException refused = new LlmException("bad key", null, false, true);

        assertThat(refused.isClientError()).isTrue();
        assertThat(refused.isRateLimited()).isFalse();
        assertThat(refused.retryAfterSeconds()).isZero();
    }
}
