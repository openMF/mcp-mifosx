package org.apache.fineract.infrastructure.mcp.tools;

/**
 * Marker interface for all MCP tools in the Fineract plugin.
 *
 * <p>All tool implementations should implement this interface for
 * consistent identification and registration.
 */
public interface FineractMcpTool {

    /**
     * Returns the category of this tool (e.g., "client", "loan", "savings", "report").
     */
    String getCategory();
}