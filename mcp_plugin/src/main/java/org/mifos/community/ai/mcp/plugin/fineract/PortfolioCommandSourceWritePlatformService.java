// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.fineract;

/**
 * Minimal interface representing Fineract's PortfolioCommandSourceWritePlatformService.
 * <p>
 * In Fineract, this is the primary entry point for all state-changing
 * (write) operations. It orchestrates:
 * <ul>
 *   <li>Permission/authorization checks</li>
 *   <li>Maker-Checker (four-eyes) workflow</li>
 *   <li>Audit logging (persists to m_portfolio_command_source)</li>
 *   <li>Transaction management</li>
 *   <li>Dispatching to the appropriate command handler</li>
 * </ul>
 * <p>
 * All mutation MCP tools (createClient, activateClient, approveLoanAccount, etc.)
 * MUST route through this service to preserve Fineract's business rules.
 * <p>
 * In a real Fineract deployment, this interface is provided by the
 * {@code fineract-core} module. This stub exists for standalone compilation.
 *
 * @see CommandWrapperBuilder
 * @see <a href="https://github.com/apache/fineract">Apache Fineract source</a>
 */
public interface PortfolioCommandSourceWritePlatformService {

    /**
     * Log and process a command through Fineract's command infrastructure.
     * <p>
     * This method:
     * <ol>
     *   <li>Validates the user has appropriate permissions</li>
     *   <li>Creates a command source audit entry</li>
     *   <li>If maker-checker is enabled, queues the command for approval</li>
     *   <li>Otherwise, executes the command immediately via the handler</li>
     * </ol>
     *
     * @param commandWrapper the command to process, built with {@link CommandWrapperBuilder}
     * @return the result of command processing (contains entity ID, changes, etc.)
     */
    Object logCommandSource(Object commandWrapper);
}
