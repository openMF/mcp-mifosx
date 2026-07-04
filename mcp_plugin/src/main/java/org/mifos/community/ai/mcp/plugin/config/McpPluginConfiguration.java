// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mifos.community.ai.mcp.plugin.fineract.ClientReadPlatformService;
import org.mifos.community.ai.mcp.plugin.fineract.PlatformSecurityContext;
import org.mifos.community.ai.mcp.plugin.mapper.ResponseMapper;
import org.mifos.community.ai.mcp.plugin.service.ClientServiceAdapter;
import org.mifos.community.ai.mcp.plugin.tools.ClientToolProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the MCP Plugin module.
 * <p>
 * This configuration class wires together the MCP service adapters and mappers.
 * Component scanning automatically registers MCP tool providers (annotated with `@Service`).
 * <p>
 * When running inside Fineract, Spring's component scanning and dependency injection
 * will automatically discover the tool providers and inject the required adapters.
 *
 * @see ClientToolProvider
 * @see ClientServiceAdapter
 */
@Configuration
@ComponentScan(basePackages = "org.mifos.community.ai.mcp.plugin")
public class McpPluginConfiguration {

    /**
     * Create a ResponseMapper bean.
     * <p>
     * Uses the provided ObjectMapper, or creates a default one.
     *
     * @param objectMapper the Jackson ObjectMapper (may be null for default)
     * @return a configured ResponseMapper
     */
    @Bean
    public ResponseMapper responseMapper(ObjectMapper objectMapper) {
        return (objectMapper != null) ? new ResponseMapper(objectMapper) : new ResponseMapper();
    }

    /**
     * Create a ClientServiceAdapter bean.
     * <p>
     * In a real Fineract deployment, {@code clientReadPlatformService} and
     * {@code securityContext} are injected from Fineract's Spring context.
     *
     * @param clientReadPlatformService Fineract's client read service
     * @param securityContext Fineract's security context for RBAC
     * @param responseMapper the response mapper
     * @return a configured ClientServiceAdapter
     */
    @Bean
    public ClientServiceAdapter clientServiceAdapter(
            ClientReadPlatformService clientReadPlatformService,
            PlatformSecurityContext securityContext,
            ResponseMapper responseMapper) {
        return new ClientServiceAdapter(clientReadPlatformService, securityContext, responseMapper);
    }
}
