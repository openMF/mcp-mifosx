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

    public record Fineract(String baseUrl) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Approval(long ttlSeconds) {}
}
