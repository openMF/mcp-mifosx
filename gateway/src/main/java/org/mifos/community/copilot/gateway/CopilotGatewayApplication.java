/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.gateway;

import org.mifos.community.copilot.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot shell around the framework-free copilot-core (ADR-001).
 * The core carries the agent loop; this shell only terminates HTTP/SSE and wires config.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class CopilotGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CopilotGatewayApplication.class, args);
    }
}
