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
 * <p>A tool the manifest does not list simply does not exist for the model, so a tool-server
 * upgrade can never widen the LLM's attack surface by annotating itself (ADR-001 §04).
 */
public final class ToolManifest {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    /** The step wording for a tool, absent for anything the manifest has not named yet. */
    private static ToolDefinition.Step step(Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        return new ToolDefinition.Step((String) node.get("running"), (String) node.get("done"));
    }

    /** A manifest value as text, so a bound written as 1 and one written as '1' both read. */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

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
                        (String) param.get("description"),
                        (String) param.get("label"),
                        (String) param.get("format"),
                        // Shown on the confirmation card unless the manifest hides it, which
                        // it does for identifiers that mean nothing to an officer.
                        !Boolean.FALSE.equals(param.get("show")),
                        Boolean.TRUE.equals(param.get("futureAllowed")),
                    text(param.get("pattern")),
                    text(param.get("mustBe")),
                    text(param.get("min")),
                    text(param.get("max")),
                    text(param.get("maxLength"))));
            }
            ToolDefinition.RestMapping rest = null;
            Map<String, Object> restNode = (Map<String, Object>) entry.get("rest");
            if (restNode != null) {
                rest = new ToolDefinition.RestMapping(
                        (String) restNode.getOrDefault("method", "GET"),
                        (String) restNode.get("path"),
                        (String) restNode.get("body"));
            }
            List<ToolDefinition.Enrich> enrich = new ArrayList<>();
            for (Map<String, Object> node : (List<Map<String, Object>>) entry.getOrDefault("enrich", List.of())) {
                Map<String, String> fields = new LinkedHashMap<>();
                ((Map<String, Object>) node.getOrDefault("fields", Map.of()))
                        .forEach((label, path) -> fields.put(label, String.valueOf(path)));
                enrich.add(new ToolDefinition.Enrich(
                        (String) node.get("path"), (String) node.get("currency"), fields));
            }
            ToolDefinition.Defaults defaults = null;
            Map<String, Object> defaultsNode = (Map<String, Object>) entry.get("defaults");
            if (defaultsNode != null) {
                Map<String, String> fields = new LinkedHashMap<>();
                ((Map<String, Object>) defaultsNode.getOrDefault("fields", Map.of()))
                        .forEach((param, path) -> fields.put(param, String.valueOf(path)));
                defaults = new ToolDefinition.Defaults((String) defaultsNode.get("path"), fields);
            }
            ToolDefinition definition = new ToolDefinition(
                    (String) entry.get("name"),
                    (String) entry.get("description"),
                    Boolean.TRUE.equals(entry.get("write")),
                    (String) entry.get("summary"),
                    params,
                    rest,
                    (List<String>) entry.getOrDefault("redactFields", List.of()),
                    enrich,
                    defaults,
                    (Map<String, String>) entry.getOrDefault("computed", Map.of()),
                    step((Map<String, Object>) entry.get("step")));
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
