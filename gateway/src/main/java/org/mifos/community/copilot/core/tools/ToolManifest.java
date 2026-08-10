/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and serves the default-deny tool manifest.
 *
 * <p>A tool the manifest does not list simply does not exist for the model — a tool-server
 * upgrade can never widen the LLM's attack surface by annotating itself (ADR-001 §04).
 */
public final class ToolManifest {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public static ToolManifest load(InputStream yamlStream) {
        Map<String, Object> root = new Yaml().load(yamlStream);
        ToolManifest manifest = new ToolManifest();
        for (Map<String, Object> entry : (List<Map<String, Object>>) root.getOrDefault("tools", List.of())) {
            List<ToolDefinition.Param> params = new ArrayList<>();
            for (Map<String, Object> param : (List<Map<String, Object>>) entry.getOrDefault("params", List.of())) {
                params.add(new ToolDefinition.Param(
                        (String) param.get("name"),
                        (String) param.getOrDefault("type", "string"),
                        Boolean.TRUE.equals(param.get("required")),
                        (String) param.get("description")));
            }
            ToolDefinition.RestMapping rest = null;
            Map<String, Object> restNode = (Map<String, Object>) entry.get("rest");
            if (restNode != null) {
                rest = new ToolDefinition.RestMapping(
                        (String) restNode.getOrDefault("method", "GET"),
                        (String) restNode.get("path"),
                        (String) restNode.get("body"));
            }
            ToolDefinition definition = new ToolDefinition(
                    (String) entry.get("name"),
                    (String) entry.get("description"),
                    Boolean.TRUE.equals(entry.get("write")),
                    (String) entry.get("summary"),
                    params,
                    rest,
                    (List<String>) entry.getOrDefault("redactFields", List.of()));
            manifest.tools.put(definition.name(), definition);
        }
        return manifest;
    }

    /** Default-deny: unknown names resolve to empty, and the loop refuses them. */
    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> all() {
        return List.copyOf(tools.values());
    }

    /** OpenAI-style schemas for every manifest tool (what the model is allowed to see). */
    public List<Map<String, Object>> openAiSchemas() {
        return tools.values().stream().map(ToolDefinition::toOpenAiSchema).toList();
    }

    public int size() {
        return tools.size();
    }
}
