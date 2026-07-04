// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.mifos.community.ai.mcp.plugin.fineract.ClientReadPlatformService;
import org.mifos.community.ai.mcp.plugin.fineract.PlatformSecurityContext;
import org.mifos.community.ai.mcp.plugin.mapper.ResponseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service adapter that replaces MifosXClient REST calls for client-related
 * operations with direct Fineract internal service calls.
 * <p>
 * This adapter is the core architectural change from the java/backoffice
 * implementation. Instead of calling:
 * <pre>
 *   mifosXClient.getClientDetailsById(clientId)  // HTTP GET /fineract-provider/api/v1/clients/{id}
 * </pre>
 * it calls:
 * <pre>
 *   platformSecurityContext.validateAccessRights("READ_CLIENT");
 *   clientReadPlatformService.retrieveOne(clientId);
 * </pre>
 * <p>
 * <strong>Security Note:</strong> Read platform services do NOT enforce
 * API-level RBAC on their own. This adapter MUST explicitly call
 * {@link PlatformSecurityContext#validateAccessRights(String)} before
 * invoking any read service, replicating the permission checks that
 * the Fineract API resource controllers normally perform.
 * <p>
 * <strong>Phase 2:</strong> Only {@code getClientDetailsById} is implemented.
 * Future phases will add: getClientByAccount, listClients, listClientAccounts,
 * createClient, activateClient, addAddress, addFamilyMember.
 *
 * @see org.mifos.community.ai.mcp.plugin.tools.ClientToolProvider
 */
public class ClientServiceAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClientServiceAdapter.class);

    /** Fineract permission code for reading client data */
    private static final String READ_CLIENT_PERMISSION = "READ_CLIENT";

    private final ClientReadPlatformService clientReadPlatformService;
    private final PlatformSecurityContext securityContext;
    private final ResponseMapper responseMapper;

    /**
     * Create a new ClientServiceAdapter.
     *
     * @param clientReadPlatformService Fineract's internal client read service
     * @param securityContext Fineract's security context for RBAC checks
     * @param responseMapper mapper for safe JSON output
     */
    public ClientServiceAdapter(
            ClientReadPlatformService clientReadPlatformService,
            PlatformSecurityContext securityContext,
            ResponseMapper responseMapper) {
        this.clientReadPlatformService = clientReadPlatformService;
        this.securityContext = securityContext;
        this.responseMapper = responseMapper;
    }

    /**
     * Get client details by ID using Fineract internal services.
     * <p>
     * Replaces: {@code mifosXClient.getClientDetailsById(clientId)}
     * <br>
     * Which called: {@code GET /fineract-provider/api/v1/clients/{clientId}}
     * <p>
     * Internal flow:
     * <ol>
     *   <li>Validate the current user has READ_CLIENT permission via PlatformSecurityContext</li>
     *   <li>Call ClientReadPlatformService.retrieveOne(clientId) to get client data</li>
     *   <li>Convert the result to a safe JSON response via ResponseMapper</li>
     * </ol>
     *
     * @param clientId the Fineract client ID (e.g., 1)
     * @return client data as a JSON node, or an error response
     */
    public JsonNode getClientDetailsById(Long clientId) {
        try {
            log.debug("Getting client details for clientId={}", clientId);
            if (clientId == null) {
                return responseMapper.createErrorResponse("Client ID is required");
            }

            // Step 1: Enforce RBAC — replicates the permission check in
            // Fineract's ClientsApiResource before it calls the read service
            securityContext.validateAccessRights(READ_CLIENT_PERMISSION);

            // Step 2: Retrieve client data via the internal read platform service
            Object clientData = clientReadPlatformService.retrieveOne(clientId);

            // Step 3: Convert to safe JSON
            log.debug("Successfully retrieved client details for clientId={}", clientId);
            return responseMapper.toJsonNode(clientData);

        } catch (Exception e) {
            log.error("Failed to get client details for clientId={}", clientId, e);
            return responseMapper.createErrorResponse(e, "getClientDetailsById");
        }
    }
}
