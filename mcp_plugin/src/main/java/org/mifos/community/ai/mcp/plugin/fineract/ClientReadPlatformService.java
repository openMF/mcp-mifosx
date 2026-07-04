// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.fineract;

/**
 * Minimal interface representing Fineract's ClientReadPlatformService.
 * <p>
 * In a real Fineract deployment, this interface is provided by the
 * {@code fineract-provider} module. This stub exists so that the
 * mcp_plugin can compile standalone (outside of a Fineract build)
 * and to document the expected contract.
 * <p>
 * When deployed into Fineract, this stub is NOT used — the real
 * Fineract implementation is injected by Spring's DI container.
 * <p>
 * <strong>Important:</strong> This interface does NOT enforce RBAC.
 * Callers must use {@link PlatformSecurityContext} to perform
 * permission checks before invoking these methods.
 *
 * @see <a href="https://github.com/apache/fineract">Apache Fineract source</a>
 */
public interface ClientReadPlatformService {

    /**
     * Retrieve a single client's details by their internal ID.
     * <p>
     * Maps to the Fineract REST endpoint:
     * {@code GET /fineract-provider/api/v1/clients/{clientId}}
     * <p>
     * The Fineract implementation returns a ClientData object.
     * In this plugin, we work with the raw data and convert to JSON.
     *
     * @param clientId the internal Fineract client ID
     * @return client data as an Object (ClientData in Fineract)
     */
    Object retrieveOne(Long clientId);
}
