package org.apache.fineract.infrastructure.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Logs information about the MCP plugin when it is activated.
 */
@Component
@Conditional(McpPluginIsEnabledCondition.class)
@Slf4j
public class McpPluginInformation implements InitializingBean {

    private final McpPluginProperties mcpProperties;

    public McpPluginInformation(McpPluginProperties mcpProperties) {
        this.mcpProperties = mcpProperties;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (mcpProperties.isEnabled()) {
            log.warn("****************************************************************");
            log.warn("*                                                              *");
            log.warn("*       Apache Fineract MCP Server Plugin Enabled              *");
            log.warn("*       Server Name: {}                              *", mcpProperties.getServerName());
            log.warn("*       Server Version: {}                                     *", mcpProperties.getServerVersion());
            log.warn("*       Transport: {}                                          *", mcpProperties.getTransportType());
            log.warn("*       Base Path: {}                                          *", mcpProperties.getBasePath());
            log.warn("*                                                              *");
            log.warn("****************************************************************");
        } else {
            log.info("Apache Fineract MCP Server Plugin is DISABLED.");
        }
    }
}