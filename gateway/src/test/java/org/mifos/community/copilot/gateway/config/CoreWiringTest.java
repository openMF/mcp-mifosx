/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.gateway.config;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider name to endpoint. Getting this wrong sends an operator's key to the wrong vendor. */
class CoreWiringTest {

    @Test
    void knownProvidersResolveTheirOwnEndpoint() {
        assertThat(CoreWiring.resolveBaseUrl("groq", null)).isEqualTo("https://api.groq.com/openai/v1");
        assertThat(CoreWiring.resolveBaseUrl("openai", null)).isEqualTo("https://api.openai.com/v1");
        assertThat(CoreWiring.resolveBaseUrl("ollama", null)).isEqualTo("http://localhost:11434/v1");
    }

    @Test
    void blankBaseUrlIsTreatedAsUnset() {
        assertThat(CoreWiring.resolveBaseUrl("openai", "   ")).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void anExplicitBaseUrlAlwaysWins() {
        assertThat(CoreWiring.resolveBaseUrl("openai", "https://my-azure.example/v1"))
                .isEqualTo("https://my-azure.example/v1");
        assertThat(CoreWiring.resolveBaseUrl("vllm", "http://vllm.internal:8000/v1"))
                .isEqualTo("http://vllm.internal:8000/v1");
    }

    @Test
    void providerNameIsCaseAndWhitespaceInsensitive() {
        assertThat(CoreWiring.resolveBaseUrl("OpenAI", null)).isEqualTo("https://api.openai.com/v1");
        assertThat(CoreWiring.resolveBaseUrl("  GROQ  ", null)).isEqualTo("https://api.groq.com/openai/v1");
    }

    @Test
    void uppercaseProviderStillResolvesUnderATurkishLocale() {
        // Turkish lower-cases ASCII I to a dotless i, so a default-locale toLowerCase() turns
        // OPENAI into "openaı" and silently misses the branch, leaving the endpoint unset.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertThat(CoreWiring.resolveBaseUrl("OPENAI", null)).isEqualTo("https://api.openai.com/v1");
        } finally {
            Locale.setDefault(original);
        }
    }
}
