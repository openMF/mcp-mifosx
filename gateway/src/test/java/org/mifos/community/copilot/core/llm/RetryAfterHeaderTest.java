/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Reading the wait a provider asked for.
 *
 * <p>The number goes straight in front of an officer as "try again in N seconds", so it is
 * worth more than the average parser. Getting it wrong in either direction costs something:
 * too low and they come back to a second refusal, too high and they give up on a service that
 * was about to work.
 */
class RetryAfterHeaderTest {

    private static HttpHeaders headers(String... pairs) {
        java.util.Map<String, List<String>> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], List.of(pairs[i + 1]));
        }
        return HttpHeaders.of(map, (name, value) -> true);
    }

    @Test
    void aPlainNumberOfSecondsIsAWait() {
        assertThat(OpenAiCompatibleLlmClient.parseDuration("30")).isEqualTo(30);
        assertThat(OpenAiCompatibleLlmClient.parseDuration("7s")).isEqualTo(7);
    }

    /** What Groq actually sends. Reading only the trailing unit called this fifty-nine seconds. */
    @Test
    void aCompoundDurationIsReadWhole() {
        assertThat(OpenAiCompatibleLlmClient.parseDuration("2m59.56s")).isEqualTo(179.56);
        assertThat(OpenAiCompatibleLlmClient.parseDuration("1h2m3s")).isEqualTo(3723);
        assertThat(OpenAiCompatibleLlmClient.parseDuration("1m")).isEqualTo(60);
    }

    @Test
    void millisecondsAreNotMinutes() {
        assertThat(OpenAiCompatibleLlmClient.parseDuration("1500ms")).isEqualTo(1.5);
    }

    /** Partly understood is not understood. Five minutes must never be read as five seconds. */
    @Test
    void aShapeItCannotReadIsNotGuessedAt() {
        assertThat(OpenAiCompatibleLlmClient.parseDuration("5 minutes")).isZero();
        assertThat(OpenAiCompatibleLlmClient.parseDuration("soon")).isZero();
        assertThat(OpenAiCompatibleLlmClient.parseDuration("")).isZero();
        assertThat(OpenAiCompatibleLlmClient.parseDuration(null)).isZero();
    }

    /**
     * An epoch timestamp on a reset header is a real provider habit. Read as seconds it is a
     * number in the billions, and "try again in fifty years" is worse than saying nothing.
     */
    @Test
    void aNumberTooLargeToBeAWaitIsIgnored() {
        assertThat(OpenAiCompatibleLlmClient.parseDuration("1787588655")).isZero();
        assertThat(OpenAiCompatibleLlmClient.parseDuration("86401")).isZero();
        // A daily token budget is the slowest thing that legitimately resets.
        assertThat(OpenAiCompatibleLlmClient.parseDuration("86400")).isEqualTo(86_400);
    }

    /** RFC 7231 allows Retry-After to carry the date it may be retried, and proxies use it. */
    @Test
    void retryAfterAcceptsADate() {
        String when = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(Instant.now().plusSeconds(45), ZoneOffset.UTC));

        assertThat(OpenAiCompatibleLlmClient.parseRetryAfter(when)).isBetween(40d, 46d);
    }

    @Test
    void aDateAlreadyPastIsNoWaitAtAll() {
        String when = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(Instant.now().minusSeconds(600), ZoneOffset.UTC));

        assertThat(OpenAiCompatibleLlmClient.parseRetryAfter(when)).isZero();
    }

    @Test
    void retryAfterIsTheProvidersOwnAnswerAndWins() {
        HttpHeaders headers = headers("retry-after", "12", "x-ratelimit-reset-tokens", "5m");

        assertThat(OpenAiCompatibleLlmClient.retryAfterSeconds(headers)).isEqualTo(12);
    }

    /**
     * Tokens and requests are separate budgets. An officer out of requests is still refused
     * when the token budget resets, so the shorter of the two would send them back too early.
     */
    @Test
    void withoutRetryAfterTheLongerBudgetDecides() {
        assertThat(OpenAiCompatibleLlmClient.retryAfterSeconds(
                headers("x-ratelimit-reset-tokens", "3s", "x-ratelimit-reset-requests", "2m30s"))).isEqualTo(150);

        assertThat(OpenAiCompatibleLlmClient.retryAfterSeconds(
                headers("x-ratelimit-reset-tokens", "2m30s", "x-ratelimit-reset-requests", "3s"))).isEqualTo(150);
    }

    @Test
    void anUnreadableRetryAfterFallsBackToTheResetHeaders() {
        HttpHeaders headers = headers("retry-after", "shortly", "x-ratelimit-reset-requests", "8s");

        assertThat(OpenAiCompatibleLlmClient.retryAfterSeconds(headers)).isEqualTo(8);
    }

    @Test
    void aProviderThatSaidNothingLeavesItAtZero() {
        assertThat(OpenAiCompatibleLlmClient.retryAfterSeconds(HttpHeaders.of(Map.of(), (n, v) -> true))).isZero();
    }

    /** Coming back fractionally early only earns a second refusal. */
    @Test
    void aFractionalWaitIsRoundedUp() {
        assertThat(OpenAiCompatibleLlmClient.retryAfterSeconds(headers("retry-after", "6.2s"))).isEqualTo(7);
    }
}
