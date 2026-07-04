// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mifos.community.ai.mcp.plugin.fineract.ClientReadPlatformService;
import org.mifos.community.ai.mcp.plugin.fineract.PlatformSecurityContext;
import org.mifos.community.ai.mcp.plugin.service.ClientServiceAdapter;
import org.mifos.community.ai.mcp.plugin.tools.ClientToolProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Context proof test.
 * <p>
 * This proves that the plugin configuration can successfully load within
 * a Spring Boot / Spring Framework application context (like Apache Fineract),
 * and that all the necessary beans are created and wired together.
 */
@SpringBootTest
@ContextConfiguration(classes = McpPluginConfiguration.class)
public class McpPluginConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ClientToolProvider clientToolProvider;

    @Autowired
    private ClientServiceAdapter clientServiceAdapter;

    // Mock the Fineract internal services that would normally be provided
    // by Fineract's core modules at runtime.
    @MockBean
    private ClientReadPlatformService clientReadPlatformService;

    @MockBean
    private PlatformSecurityContext platformSecurityContext;

    @MockBean
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Spring Application Context loads and wires MCP plugin beans")
    void contextLoads() {
        assertNotNull(context, "Spring application context should load");
        
        // Verify ClientToolProvider bean exists
        assertNotNull(clientToolProvider, "ClientToolProvider bean should be present");
        
        // Verify ClientServiceAdapter bean exists
        assertNotNull(clientServiceAdapter, "ClientServiceAdapter bean should be present");
        
        // Verify the beans are in the context
        assertTrue(context.containsBean("clientToolProvider"));
        assertTrue(context.containsBean("clientServiceAdapter"));
        assertTrue(context.containsBean("responseMapper"));
    }
}
