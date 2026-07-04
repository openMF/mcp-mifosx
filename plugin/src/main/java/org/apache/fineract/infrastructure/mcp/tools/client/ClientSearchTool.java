package org.apache.fineract.infrastructure.mcp.tools.client;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.search.SearchService;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool for searching clients in Apache Fineract.
 *
 * <p>This tool allows AI models to search for clients by various criteria
 * including name, external ID, or account number.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClientSearchTool implements FineractMcpTool {

    private final ClientReadPlatformService clientReadPlatformService;

    @Override
    public String getCategory() {
        return "client";
    }

    @Tool(name = "fineract_client_search",
          description = "Search for clients in Apache Fineract by name, external ID, or other criteria. "
              + "Returns a list of matching clients with their basic information including ID, display name, "
              + "office, and status. Use this tool to find clients before performing operations on them.")
    public List<ClientSearchResult> searchClients(
            @ToolParam(description = "The search query string (matches against client name, account number, or external ID)")
            String query,
            @ToolParam(description = "Maximum number of results to return (default: 20)", required = false)
            Integer maxResults) {

        log.info("MCP Tool: Searching clients with query: '{}'", query);

        int limit = (maxResults != null && maxResults > 0) ? maxResults : 20;

        try {
            // Use Fineract's search service to find matching clients
            var searchResults = clientReadPlatformService.retrieveAllSearchResults(query, limit);

            return searchResults.stream()
                    .map(clientData -> new ClientSearchResult(
                            clientData.getId(),
                            clientData.getDisplayName(),
                            clientData.getExternalId(),
                            clientData.getOfficeName(),
                            clientData.getStatus() != null ? clientData.getStatus().getValue() : "Unknown"
                    ))
                    .toList();

        } catch (Exception e) {
            log.error("Error searching clients with query: '{}'", query, e);
            throw new RuntimeException("Failed to search clients: " + e.getMessage(), e);
        }
    }

    /**
     * Result record for client search operations.
     */
    public record ClientSearchResult(
            Long id,
            String displayName,
            String externalId,
            String officeName,
            String status
    ) {}
}