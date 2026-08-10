/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.gateway.web;

import org.mifos.community.copilot.core.tools.ToolManifest;
import org.mifos.community.copilot.gateway.config.GatewayProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health + meta (wire contract v1). The web-app's feature flag polls /health so an LLM outage
 * degrades to a hidden panel with a banner instead of surfacing raw errors.
 */
@RestController
@RequestMapping("/copilot/api/v1")
public class MetaController {

    private final GatewayProperties properties;
    private final ToolManifest manifest;

    public MetaController(GatewayProperties properties, ToolManifest manifest) {
        this.properties = properties;
        this.manifest = manifest;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "llm", Map.of(
                        "provider", properties.llm().provider(),
                        "configured", !"mock".equalsIgnoreCase(properties.llm().provider())),
                "tools", Map.of("count", manifest.size()));
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of(
                "provider", properties.llm().provider(),
                "model", properties.llm().model(),
                "data_residency", properties.llm().dataResidency(),
                "contract_version", "1.0.0");
    }
}
