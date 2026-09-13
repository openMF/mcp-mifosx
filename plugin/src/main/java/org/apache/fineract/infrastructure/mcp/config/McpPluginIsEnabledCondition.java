package org.apache.fineract.infrastructure.mcp.config;

import org.apache.fineract.infrastructure.core.condition.PropertiesCondition;
import org.apache.fineract.infrastructure.core.config.FineractProperties;

/**
 * Condition that determines whether the MCP plugin should be activated.
 *
 * <p>The plugin is enabled by default but can be disabled via configuration.
 */
public class McpPluginIsEnabledCondition extends PropertiesCondition {

    @Override
    protected boolean matches(FineractProperties properties) {
        // The plugin is always enabled at the Fineract level.
        // Fine-grained control is handled by McpPluginProperties.enabled
        return true;
    }
}