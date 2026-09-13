package org.apache.fineract.infrastructure.mcp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the MCP Server Plugin.
 *
 * <p>These properties can be configured in application.yml or application.properties
 * under the prefix "fineract.mcp".
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fineract.mcp")
public class McpPluginProperties {

    /**
     * Whether the MCP plugin is enabled.
     */
    private boolean enabled = true;

    /**
     * The name of the MCP server as advertised to clients.
     */
    private String serverName = "fineract-mcp-server";

    /**
     * The version of the MCP server.
     */
    private String serverVersion = "1.0.0";

    /**
     * The transport type for MCP communication (SSE or STDIO).
     */
    private String transportType = "SSE";

    /**
     * The port for the MCP SSE endpoint (if different from main server).
     */
    private int ssePort = 8080;

    /**
     * Base path for MCP endpoints.
     */
    private String basePath = "/mcp";

    /**
     * Whether to require authentication for MCP tool calls.
     */
    private boolean requireAuth = true;

    /**
     * Maximum number of concurrent tool executions.
     */
    private int maxConcurrentTools = 10;

    /**
     * Timeout in seconds for tool execution.
     */
    private int toolTimeoutSeconds = 30;

    /**
     * Comma-separated list of enabled tool categories (client, loan, savings, report).
     * Empty means all tools are enabled.
     */
    private String enabledToolCategories = "";
}