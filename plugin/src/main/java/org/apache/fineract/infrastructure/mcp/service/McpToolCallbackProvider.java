package org.apache.fineract.infrastructure.mcp.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.mcp.config.McpPluginProperties;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * Custom tool callback provider that filters MCP tools based on configuration.
 *
 * <p>This provider respects the enabled tool categories configuration,
 * allowing administrators to selectively enable or disable groups of tools.
 */
@Slf4j
public class McpToolCallbackProvider {

    private final McpPluginProperties mcpProperties;

    public McpToolCallbackProvider(McpPluginProperties mcpProperties) {
        this.mcpProperties = mcpProperties;
    }

    /**
     * Creates tool callbacks from the provided tool objects, filtering by enabled categories.
     *
     * @param tools The tool objects to register
     * @return List of ToolCallback instances for enabled tools
     */
    public List<ToolCallback> createToolCallbacks(Object... tools) {
        Set<String> enabledCategories = getEnabledCategories();

        List<ToolCallback> allCallbacks = new ArrayList<>();
        List<Object> filteredTools = new ArrayList<>();

        for (Object tool : tools) {
            if (tool instanceof FineractMcpTool fineractTool) {
                String category = fineractTool.getCategory();

                if (enabledCategories.isEmpty() || enabledCategories.contains(category)) {
                    filteredTools.add(tool);
                    log.debug("MCP Tool enabled: {} (category: {})", tool.getClass().getSimpleName(), category);
                } else {
                    log.debug("MCP Tool disabled: {} (category: {})", tool.getClass().getSimpleName(), category);
                }
            } else {
                // Non-FineractMcpTool objects are always included
                filteredTools.add(tool);
            }
        }

        // Use Spring AI's ToolCallbacks to create the actual callbacks
        for (Object tool : filteredTools) {
            ToolCallback[] callbacks = ToolCallbacks.from(tool);
            allCallbacks.addAll(Arrays.asList(callbacks));
        }

        return allCallbacks;
    }

    /**
     * Parses the enabled tool categories from configuration.
     *
     * @return Set of enabled category names, or empty set if all are enabled
     */
    private Set<String> getEnabledCategories() {
        String categories = mcpProperties.getEnabledToolCategories();

        if (categories == null || categories.isBlank()) {
            return Set.of(); // Empty means all enabled
        }

        return Arrays.stream(categories.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}