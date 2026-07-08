package org.apache.fineract.infrastructure.mcp.tools.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool for retrieving detailed client information from Apache Fineract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClientDetailsTool implements FineractMcpTool {

    private final ClientReadPlatformService clientReadPlatformService;

    @Override
    public String getCategory() {
        return "client";
    }

    @Tool(name = "fineract_client_get",
          description = "Retrieve detailed information about a specific client in Apache Fineract by their client ID. "
              + "Returns comprehensive client data including personal details, address, office assignment, "
              + "activation date, and current status. Use this tool to get full client details before "
              + "performing account operations.")
    public ClientDetailResult getClientDetails(
            @ToolParam(description = "The unique identifier of the client")
            Long clientId) {

        log.info("MCP Tool: Getting details for client ID: {}", clientId);

        try {
            ClientData clientData = clientReadPlatformService.retrieveOne(clientId);

            return new ClientDetailResult(
                    clientData.getId(),
                    clientData.getDisplayName(),
                    clientData.getFirstname(),
                    clientData.getLastname(),
                    clientData.getExternalId() != null ? clientData.getExternalId().getValue() : null,
                    clientData.getOfficeName(),
                    clientData.getActivationDate() != null ? clientData.getActivationDate().toString() : null,
                    clientData.getStatus() != null ? clientData.getStatus().getValue() : "Unknown",
                    clientData.getMobileNo(),
                    clientData.getEmailAddress()
            );

        } catch (Exception e) {
            log.error("Error retrieving client details for ID: {}", clientId, e);
            throw new RuntimeException("Failed to retrieve client details: " + e.getMessage(), e);
        }
    }

    /**
     * Result record for client detail retrieval.
     */
    public record ClientDetailResult(
            Long id,
            String displayName,
            String firstName,
            String lastName,
            String externalId,
            String officeName,
            String activationDate,
            String status,
            String mobileNumber,
            String emailAddress
    ) {}
}