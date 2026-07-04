// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

/**
 * Stub interfaces for Apache Fineract internal services.
 * <p>
 * This package contains minimal interface definitions that mirror the
 * corresponding Fineract internal APIs. They exist so that the mcp_plugin
 * module can compile standalone (outside of a full Fineract build).
 * <p>
 * <strong>At runtime inside Fineract</strong>, Spring will inject the real
 * Fineract implementations, NOT these stubs. The stubs are never used
 * in a deployed environment.
 * <p>
 * <strong>When integrating with Fineract's Gradle build</strong>, these stubs
 * should be replaced with {@code compileOnly} project dependencies:
 * <pre>
 *   compileOnly project(':fineract-core')
 *   compileOnly project(':fineract-provider')
 * </pre>
 *
 * @see org.mifos.community.ai.mcp.plugin.service
 */
package org.mifos.community.ai.mcp.plugin.fineract;
