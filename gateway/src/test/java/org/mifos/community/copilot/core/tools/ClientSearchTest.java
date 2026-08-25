/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Looking a client up by name.
 *
 * <p>This exists because of a bug that a test would have caught and a manual check did not.
 * Search was moved to a query parameter Fineract does not bind, so the filter was dropped and
 * every client in the office came back. Checked by hand against a database holding one client,
 * where "filtered to the one match" and "ignored the filter and returned all one of them" are
 * the same number, so it read as a pass.
 *
 * <p>The lesson is in {@link #theFilterIsOneFineractActuallyBinds()}: pin the parameter name,
 * because a wrong one fails silently and open.
 */
class ClientSearchTest {

    private static final ToolManifest MANIFEST = ToolManifest.load(ClientSearchTest.class.getResourceAsStream("/tools.yaml"));

    private static ToolDefinition searchTool() {
        return MANIFEST.find("mifos_client_search").orElseThrow();
    }

    /**
     * An unbound parameter is not an error to Fineract, it is silently dropped, and the tool
     * then hands the model every client in the office instead of the one being asked about.
     */
    @Test
    void theFilterIsOneFineractActuallyBinds() {
        String path = searchTool().rest().path();

        assertThat(path).isEqualTo("/clients?displayName={query}");
        assertThat(path).doesNotContain("?name=");
    }

    @Test
    void theQueryIsDeclaredAsAName() {
        ToolDefinition.Param query = searchTool().params().stream()
                .filter((p) -> p.name().equals("query"))
                .findFirst()
                .orElseThrow();

        assertThat(query.isName()).isTrue();
        assertThat(query.required()).isTrue();
    }

    /** An officer types a name the way people type names, and means the same person each time. */
    @Test
    void howeverItIsTypedItGoesOnTheWireAsAName() {
        assertThat(FineractRestToolExecutor.asStoredName("aisha")).isEqualTo("Aisha");
        assertThat(FineractRestToolExecutor.asStoredName("AISHA")).isEqualTo("Aisha");
        assertThat(FineractRestToolExecutor.asStoredName("aIsHa")).isEqualTo("Aisha");
        assertThat(FineractRestToolExecutor.asStoredName("Aisha")).isEqualTo("Aisha");
    }

    @Test
    void everyWordOfAFullNameIsANameInItsOwnRight() {
        assertThat(FineractRestToolExecutor.asStoredName("aisha bello")).isEqualTo("Aisha Bello");
        assertThat(FineractRestToolExecutor.asStoredName("MARIA DE SOUZA")).isEqualTo("Maria De Souza");
    }

    /**
     * Confirmed against a real Fineract: displayName=McDonald finds Ronald McDonald and
     * displayName=Mcdonald finds nobody. Capitalising every word is the wrong answer for any
     * name that keeps a capital somewhere a general rule would not put one.
     */
    @Test
    void aNameSpelledDeliberatelyIsLeftAlone() {
        assertThat(FineractRestToolExecutor.asStoredName("McDonald")).isEqualTo("McDonald");
        assertThat(FineractRestToolExecutor.asStoredName("O'Connor")).isEqualTo("O'Connor");
        assertThat(FineractRestToolExecutor.asStoredName("de la Cruz")).isEqualTo("de la Cruz");
        assertThat(FineractRestToolExecutor.asStoredName("van der Berg")).isEqualTo("van der Berg");
        assertThat(FineractRestToolExecutor.asStoredName("Ronald McDonald")).isEqualTo("Ronald McDonald");
    }

    /** Typing it all one way says nothing about the capitals, so there is nothing to preserve. */
    @Test
    void aNameTypedFlatIsStillCapitalised() {
        assertThat(FineractRestToolExecutor.asStoredName("ronald mcdonald")).isEqualTo("Ronald Mcdonald");
        assertThat(FineractRestToolExecutor.asStoredName("DE LA CRUZ")).isEqualTo("De La Cruz");
    }

    /** Hyphens and apostrophes start a word in a name as surely as a space does. */
    @Test
    void punctuationInsideANameStartsANewWord() {
        assertThat(FineractRestToolExecutor.asStoredName("anne-marie")).isEqualTo("Anne-Marie");
        assertThat(FineractRestToolExecutor.asStoredName("o'brien")).isEqualTo("O'Brien");
    }

    @Test
    void nothingTypedIsNothingSent() {
        assertThat(FineractRestToolExecutor.asStoredName("")).isEmpty();
        assertThat(FineractRestToolExecutor.asStoredName(null)).isEmpty();
    }

    /** Searching is a read. It must never have become anything else. */
    @Test
    void searchingForSomebodyChangesNothing() {
        assertThat(searchTool().write()).isFalse();
        assertThat(searchTool().rest().method()).isEqualTo("GET");
    }

    /**
     * A search returns whole client records. The name has to survive or the answer is useless,
     * but the phone number and the national id are nobody's business here.
     */
    @Test
    void aSearchStillHidesWhatTheOfficerDidNotAskFor() {
        assertThat(searchTool().redactFields()).containsExactlyInAnyOrder("externalId", "mobileNo");
    }
}
