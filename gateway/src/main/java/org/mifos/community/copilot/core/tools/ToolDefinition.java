/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One tool from the reviewed, version-controlled manifest ({@code tools.yaml}).
 *
 * <p>The manifest — not the tool server, not the model — is the enforcement authority for what
 * the LLM may touch and what counts as a write (ADR-001 §04, default-deny).
 */
public record ToolDefinition(String name, String description, boolean write, String summaryTemplate,
        List<Param> params, RestMapping rest, List<String> redactFields) {

    /** One declared parameter. */
    public record Param(String name, String type, boolean required, String description) {}

    /** How the direct-REST executor maps this tool onto the Fineract API. */
    public record RestMapping(String method, String path, String bodyTemplate) {}

    /** OpenAI-style function schema handed to the model. */
    public Map<String, Object> toOpenAiSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = params == null ? List.of()
                : params.stream().filter(Param::required).map(Param::name).toList();
        if (params != null) {
            for (Param param : params) {
                properties.put(param.name(), Map.of(
                        "type", param.type() == null ? "string" : param.type(),
                        "description", param.description() == null ? "" : param.description()));
            }
        }
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description == null ? "" : description,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", properties,
                                "required", required,
                                // Advisory for many providers, but it steers the model away from
                                // inventing args; the loop refuses undeclared args regardless.
                                "additionalProperties", false)));
    }

    /** Officer-facing one-liner for the approval card, e.g. "Approve loan #{loanId}". */
    public String humanSummary(Map<String, Object> args) {
        String template = summaryTemplate != null ? summaryTemplate : (description != null ? description : name);
        String out = template;
        if (args != null) {
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                out = out.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
        }
        return out;
    }
}
