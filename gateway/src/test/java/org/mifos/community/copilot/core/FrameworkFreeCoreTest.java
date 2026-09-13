/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration insurance (ADR-001): the copilot-core package must stay framework-free so the
 * mentor's Fineract plugin can embed it unchanged. This test fails the build the moment a
 * Spring/Quarkus/Jakarta import sneaks into core under deadline pressure.
 */
class FrameworkFreeCoreTest {

    private static final List<String> FORBIDDEN = List.of(
            "import org.springframework",
            "import jakarta.",
            "import io.quarkus",
            "import reactor.");

    @Test
    void coreHasNoFrameworkImports() throws IOException {
        Path coreSources = Path.of("src", "main", "java", "org", "mifos", "community", "copilot", "core");
        assertThat(coreSources).exists();
        try (Stream<Path> files = Files.walk(coreSources)) {
            files.filter((path) -> path.toString().endsWith(".java")).forEach((path) -> {
                String source;
                try {
                    source = Files.readString(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                for (String forbidden : FORBIDDEN) {
                    assertThat(source)
                            .withFailMessage("%s must stay framework-free but imports '%s'", path, forbidden)
                            .doesNotContain(forbidden);
                }
            });
        }
    }
}
