/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.agentic.loan.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Formats one or more {@link VisionAnalysis} results into the independent Fineract loan note
 * for {@code OLLAMA_VISION_MODEL} (separate from the underwriting note driven by {@code OLLAMA_MODEL}).
 * <p>
 * Lives in the model package so note content is derived from domain data, not from
 * infrastructure config on the Fineract client.
 */
public final class VisionAnalysisNote {

    private VisionAnalysisNote() {}

    /**
     * @return note text, or {@code null} when there is nothing useful to attach
     */
    public static String build(String workflowId, List<VisionAnalysis> analyses) {
        if (analyses == null || analyses.isEmpty()) {
            return null;
        }
        boolean any = false;
        for (VisionAnalysis a : analyses) {
            if (a != null && a.hasContent()) {
                any = true;
                break;
            }
        }
        if (!any) {
            return null;
        }

        String modelLabel = resolveVisionModelLabel(analyses);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Vision model document analysis (OLLAMA_VISION_MODEL) ===\n");
        sb.append("Vision model (OLLAMA_VISION_MODEL): ").append(modelLabel).append("\n");
        if (workflowId != null && !workflowId.isBlank()) {
            sb.append("Workflow ID (loan externalId): ").append(workflowId).append("\n");
        }
        sb.append("Source: CurpDocumentAgent (PDF → PNG → Ollama vision)\n");
        sb.append("This note is independent of the underwriting (OLLAMA_MODEL) decision note.\n\n");

        int idx = 0;
        for (VisionAnalysis a : analyses) {
            if (a == null || !a.hasContent()) {
                continue;
            }
            idx++;
            sb.append(String.format("--- Document #%d ---%n", idx));
            if (a.getPath() != null) {
                sb.append("Path: ").append(a.getPath()).append("\n");
            }
            if (a.getDocType() != null) {
                sb.append("Type: ").append(a.getDocType()).append("\n");
            }
            sb.append("Overall valid: ").append(a.isValid()).append("\n");
            if (a.getVisionModel() != null && !a.getVisionModel().isBlank()
                    && (analyses.size() > 1 || !a.getVisionModel().equals(modelLabel))) {
                sb.append("Vision model: ").append(a.getVisionModel()).append("\n");
            }
            if (a.getValidationMessages() != null && !a.getValidationMessages().isEmpty()) {
                sb.append("Validation messages:\n");
                for (String m : a.getValidationMessages()) {
                    sb.append("  - ").append(m).append("\n");
                }
            }
            if (a.getCurpClave() != null) {
                sb.append("CURP clave: ").append(a.getCurpClave()).append("\n");
            }
            if (a.getFullName() != null) {
                sb.append("Extracted name: ").append(a.getFullName()).append("\n");
            }
            if (a.getIssueDate() != null) {
                sb.append("Issue date: ").append(a.getIssueDate()).append("\n");
            }
            if (a.getRegistrationEntity() != null) {
                sb.append("Registration entity: ").append(a.getRegistrationEntity()).append("\n");
            }
            if (a.getConfidence() != null) {
                sb.append("Confidence: ").append(a.getConfidence()).append("\n");
            }
            if (a.getVisionJson() != null && !a.getVisionJson().isBlank()) {
                sb.append("\n--- Vision model raw analysis output ---\n");
                String vj = a.getVisionJson();
                if (vj.length() > 3000) {
                    vj = vj.substring(0, 2997) + "...";
                }
                sb.append(vj).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Prefer the first non-blank model name; if several differ, list unique names. */
    private static String resolveVisionModelLabel(List<VisionAnalysis> analyses) {
        Set<String> models = new LinkedHashSet<>();
        for (VisionAnalysis a : analyses) {
            if (a != null && a.getVisionModel() != null && !a.getVisionModel().isBlank()) {
                models.add(a.getVisionModel().trim());
            }
        }
        if (models.isEmpty()) {
            return "(unknown)";
        }
        if (models.size() == 1) {
            return models.iterator().next();
        }
        return String.join(", ", models);
    }
}
