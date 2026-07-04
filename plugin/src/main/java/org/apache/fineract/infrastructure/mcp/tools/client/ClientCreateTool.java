package org.apache.fineract.infrastructure.mcp.tools.client;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.apache.fineract.portfolio.client.api.ClientApiConstants;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool for creating new clients in Apache Fineract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClientCreateTool implements FineractMcpTool {

    private final ClientWritePlatformService clientWritePlatformService;

    @Override
    public String getCategory() {
        return "client";
    }

    @Tool(name = "fineract_client_create",
          description = "Create a new client in Apache Fineract. Requires the client's first name, last name, "
              + "and the office ID where the client should be registered. Optionally accepts middle name, "
              + "external ID, mobile number, and email address. Returns the newly created client's ID.")
    public ClientCreateResult createClient(
            @ToolParam(description = "The client's first name")
            String firstName,
            @ToolParam(description = "The client's last name")
            String lastName,
            @ToolParam(description = "The ID of the office where the client will be registered")
            Long officeId,
            @ToolParam(description = "The client's middle name (optional)", required = false)
            String middleName,
            @ToolParam(description = "External identifier for the client (optional)", required = false)
            String externalId,
            @ToolParam(description = "Mobile phone number (optional)", required = false)
            String mobileNumber,
            @ToolParam(description = "Email address (optional)", required = false)
            String emailAddress) {

        log.info("MCP Tool: Creating client '{}' '{}' in office {}", firstName, lastName, officeId);

        try {
            // Build the command JSON for client creation
            var commandJson = new java.util.HashMap<String, Object>();
            commandJson.put(ClientApiConstants.firstnameParamName, firstName);
            commandJson.put(ClientApiConstants.lastnameParamName, lastName);
            commandJson.put(ClientApiConstants.officeIdParamName, officeId);
            commandJson.put(ClientApiConstants.activeParamName, true);
            commandJson.put(ClientApiConstants.localeParamName, "en");
            commandJson.put(ClientApiConstants.dateFormatParamName, "dd MMMM yyyy");

            if (middleName != null && !middleName.isBlank()) {
                commandJson.put(ClientApiConstants.middlenameParamName, middleName);
            }
            if (externalId != null && !externalId.isBlank()) {
                commandJson.put(ClientApiConstants.externalIdParamName, externalId);
            }
            if (mobileNumber != null && !mobileNumber.isBlank()) {
                commandJson.put(ClientApiConstants.mobileNoParamName, mobileNumber);
            }
            if (emailAddress != null && !emailAddress.isBlank()) {
                commandJson.put(ClientApiConstants.emailAddressParamName, emailAddress);
            }

            // Set activation date to today
            commandJson.put(ClientApiConstants.activationDateParamName,
                    LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy")));

            CommandProcessingResult result = clientWritePlatformService.createClient(
                    new org.apache.fineract.commands.domain.CommandWrapper.Builder()
                            .createClient()
                            .build(),
                    new com.google.gson.JsonParser().parse(new com.google.gson.Gson().toJson(commandJson)).getAsJsonObject()
            );

            return new ClientCreateResult(
                    result.getResourceId(),
                    firstName + " " + lastName,
                    officeId,
                    "Client created successfully"
            );

        } catch (Exception e) {
            log.error("Error creating client '{}' '{}'", firstName, lastName, e);
            throw new RuntimeException("Failed to create client: " + e.getMessage(), e);
        }
    }

    /**
     * Result record for client creation.
     */
    public record ClientCreateResult(
            Long clientId,
            String displayName,
            Long officeId,
            String message
    ) {}
}