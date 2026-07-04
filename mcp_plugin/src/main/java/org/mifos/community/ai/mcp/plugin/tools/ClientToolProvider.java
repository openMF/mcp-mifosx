// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.mifos.community.ai.mcp.plugin.service.ClientServiceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool Provider for client-related operations.
 * <p>
 * Exposes Fineract client tools to MCP clients using Spring AI MCP Server
 * annotations. Tool names, descriptions, and argument semantics are preserved
 * from the existing {@code java/backoffice/MifosXServer.java} implementation.
 * <p>
 * <strong>Key difference from java/backoffice:</strong> This provider does
 * NOT use MifosXClient or HTTP calls. It delegates to
 * {@link ClientServiceAdapter} which calls Fineract internal services directly.
 * <p>
 * <strong>Phase 2:</strong> Only {@code getClientDetailsById} is implemented.
 * Future phases will add:
 * <ul>
 *   <li>{@code getClientByAccount} — Search for a client by account number</li>
 *   <li>{@code listClients} — List/search clients with pagination</li>
 *   <li>{@code listClientAccounts} — List accounts for a specific client</li>
 *   <li>{@code createClient} — Create a new client</li>
 *   <li>{@code activateClient} — Activate a pending client</li>
 *   <li>{@code addAddress} — Add an address to a client</li>
 *   <li>{@code addFamilyMember} — Add a family member to a client</li>
 * </ul>
 * <p>
 * In a Spring AI MCP Server context, this class would be annotated with
 * {@code @Service} and methods would use {@code @McpTool} annotations.
 * For Phase 2 (standalone compilation), annotations are documented in
 * comments and the delegation pattern is fully functional.
 *
 * @see ClientServiceAdapter
 */
@Service
public class ClientToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ClientToolProvider.class);

    private final ClientServiceAdapter clientServiceAdapter;

    /**
     * Create a new ClientToolProvider.
     *
     * @param clientServiceAdapter the adapter that handles Fineract internal calls
     */
    public ClientToolProvider(ClientServiceAdapter clientServiceAdapter) {
        this.clientServiceAdapter = clientServiceAdapter;
    }

    /**
     * MCP Tool: Get client by id.
     * <p>
     * Exactly matches the existing tool in MifosXServer.java:
     * <pre>
     * {@code @Tool(description = "Get client by id")}
     * {@code JsonNode getClientDetailsById(@ToolArg(description = "Client Id (e.g. 1)") Integer clientId)}
     * </pre>
     * <p>
     * When running inside Fineract with Spring AI MCP Server, this method
     * would be annotated:
     * <pre>
     * {@code @McpTool(description = "Get client by id")}
     * </pre>
     *
     * @param clientId the Fineract client ID (e.g., 1)
     * @return client details as a JSON node
     */
    @Tool(description = "Get client by id")
    public JsonNode getClientDetailsById(
            @ToolParam(description = "Client Id (e.g. 1)") Integer clientId) {
        log.info("MCP tool invoked: getClientDetailsById(clientId={})", clientId);
        return clientServiceAdapter.getClientDetailsById(clientId == null ? null : clientId.longValue());
    }
}
