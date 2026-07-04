// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.fineract;

/**
 * Minimal interface representing Fineract's PlatformSecurityContext.
 * <p>
 * In Fineract, this context provides:
 * <ul>
 *   <li>The currently authenticated user (AppUser)</li>
 *   <li>Permission validation (e.g., checking READ_CLIENT permission)</li>
 *   <li>Tenant context for multi-tenant isolation</li>
 * </ul>
 * <p>
 * <strong>Critical:</strong> Read platform services do NOT enforce RBAC
 * on their own. The MCP plugin MUST call
 * {@link #validateAccessRights(String)} before invoking any read service
 * to replicate the permission checks that Fineract API resource classes
 * normally perform.
 * <p>
 * In a real Fineract deployment, this interface is provided by the
 * {@code fineract-core} module. This stub exists for standalone compilation.
 *
 * @see <a href="https://github.com/apache/fineract">Apache Fineract source</a>
 */
public interface PlatformSecurityContext {

    /**
     * Retrieve the currently authenticated Fineract user.
     * <p>
     * In Fineract, this returns an AppUser object. For this stub,
     * it returns Object to avoid pulling in AppUser dependencies.
     *
     * @return the authenticated user
     * @throws RuntimeException if no user is authenticated
     */
    Object authenticatedUser();

    /**
     * Validate that the current user has the specified permission.
     * <p>
     * Examples of permission strings:
     * <ul>
     *   <li>{@code "READ_CLIENT"}</li>
     *   <li>{@code "CREATE_CLIENT"}</li>
     *   <li>{@code "READ_SAVINGSACCOUNT"}</li>
     *   <li>{@code "APPROVE_LOAN"}</li>
     * </ul>
     *
     * @param permissionCode the Fineract permission code to check
     * @throws RuntimeException if the current user lacks the permission
     */
    void validateAccessRights(String permissionCode);
}
