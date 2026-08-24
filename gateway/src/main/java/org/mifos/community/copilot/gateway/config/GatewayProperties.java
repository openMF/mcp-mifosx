/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Gateway configuration. The LLM API key lives HERE, server-side, never in the browser;
 * the Fineract-plugin phase will read it from Fineract's third-party service tables instead.
 */
@ConfigurationProperties(prefix = "copilot")
public record GatewayProperties(Llm llm, Fineract fineract, Cors cors, Approval approval) {

    public record Llm(String provider, String baseUrl, String apiKey, String model, String dataResidency) {}

    /**
     * Where Fineract is, and under what path.
     *
     * <p>{@code apiPath} exists because a bare Fineract serves at
     * {@code /fineract-provider/api/v1} and a Fineract behind an API manager does not. The
     * Mifos community sandbox publishes the same server at {@code /1.0/core/api/v1}, and the
     * web app is already told this separately as FINERACT_API_PROVIDER, so the gateway had no
     * business assuming it.
     */
    public record Fineract(String baseUrl, String apiPath) {

        public Fineract {
            apiPath = apiPath == null || apiPath.isBlank() ? DEFAULT_API_PATH : apiPath.replaceAll("/+$", "");
        }

        public static final String DEFAULT_API_PATH = "/fineract-provider/api/v1";
    }

    public record Cors(List<String> allowedOrigins) {}

    public record Approval(long ttlSeconds) {}
}
