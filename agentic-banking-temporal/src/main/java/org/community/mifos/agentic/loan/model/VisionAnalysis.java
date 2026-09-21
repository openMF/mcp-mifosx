/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.agentic.loan.model;

import org.community.mifos.agentic.loan.document.CurpDocument;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain model for a single document analysed by the vision model ({@code OLLAMA_VISION_MODEL}).
 * <p>
 * Mirrors {@link LoanDecision#getLlmModel()} for the text LLM: the model identity travels
 * with the analysis result through the workflow, rather than being re-read from infrastructure
 * config at Fineract write-back time.
 */
public class VisionAnalysis implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Ollama vision model id, e.g. {@code qwen3-vl:8b} ({@code OLLAMA_VISION_MODEL}). */
    private String visionModel;
    private String path;
    private String docType;
    private boolean valid;
    private List<String> validationMessages = new ArrayList<>();
    private String curpClave;
    private String fullName;
    private String issueDate;
    private String registrationEntity;
    private Double confidence;
    /** Raw JSON / text returned by the vision model. */
    private String visionJson;

    public VisionAnalysis() {}

    public static VisionAnalysis fromCurpDocument(String path, CurpDocument curp) {
        VisionAnalysis v = new VisionAnalysis();
        v.path = path;
        v.docType = "CURP";
        if (curp == null) {
            v.valid = false;
            return v;
        }
        v.visionModel = curp.getVisionModel();
        v.valid = curp.isOverallValid();
        if (curp.getValidationMessages() != null) {
            v.validationMessages = new ArrayList<>(curp.getValidationMessages());
        }
        v.curpClave = curp.getCurpClave();
        v.fullName = curp.getFullName();
        v.issueDate = curp.getIssueDate() != null ? curp.getIssueDate().toString() : null;
        v.registrationEntity = curp.getRegistrationEntity();
        v.confidence = curp.getConfidence();
        v.visionJson = curp.getVisionJson();
        return v;
    }

    /**
     * Rebuild from the Temporal-friendly map produced by {@code LoanActivities.processDocument}.
     */
    @SuppressWarnings("unchecked")
    public static VisionAnalysis fromProcessResult(Map<String, Object> doc) {
        VisionAnalysis v = new VisionAnalysis();
        if (doc == null) {
            return v;
        }
        v.path = str(doc.get("path"));
        v.docType = str(doc.get("docType"));
        v.valid = Boolean.TRUE.equals(doc.get("valid"));
        Object topModel = doc.get("visionModel");
        if (topModel != null && !topModel.toString().isBlank()) {
            v.visionModel = topModel.toString().trim();
        }
        Object msgs = doc.get("validationMessages");
        if (msgs instanceof List<?> list) {
            for (Object m : list) {
                if (m != null) {
                    v.validationMessages.add(m.toString());
                }
            }
        }
        Object extracted = doc.get("extracted");
        if (extracted instanceof Map<?, ?> em) {
            if (v.visionModel == null) {
                Object emModel = em.get("visionModel");
                if (emModel != null && !emModel.toString().isBlank()) {
                    v.visionModel = emModel.toString().trim();
                }
            }
            v.curpClave = str(em.get("curpClave"));
            v.fullName = str(em.get("fullName"));
            v.issueDate = str(em.get("issueDate"));
            v.registrationEntity = str(em.get("registrationEntity"));
            Object conf = em.get("confidence");
            if (conf instanceof Number n) {
                v.confidence = n.doubleValue();
            } else if (conf != null) {
                try {
                    v.confidence = Double.parseDouble(conf.toString());
                } catch (NumberFormatException ignored) {
                    // leave null
                }
            }
            v.visionJson = str(em.get("visionJson"));
            if (v.docType == null) {
                v.docType = str(em.get("type"));
            }
        }
        return v;
    }

    public static List<VisionAnalysis> fromProcessResults(List<Map<String, Object>> documentResults) {
        if (documentResults == null || documentResults.isEmpty()) {
            return List.of();
        }
        List<VisionAnalysis> out = new ArrayList<>(documentResults.size());
        for (Map<String, Object> doc : documentResults) {
            out.add(fromProcessResult(doc));
        }
        return out;
    }

    public boolean hasContent() {
        return (visionJson != null && !visionJson.isBlank())
                || (curpClave != null && !curpClave.isBlank())
                || (docType != null && !docType.isBlank())
                || (path != null && !path.isBlank());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("path", path);
        m.put("docType", docType);
        m.put("valid", valid);
        m.put("visionModel", visionModel);
        m.put("validationMessages", new ArrayList<>(validationMessages));
        Map<String, Object> extracted = new HashMap<>();
        extracted.put("type", docType != null ? docType : "CURP");
        extracted.put("curpClave", curpClave);
        extracted.put("fullName", fullName);
        extracted.put("issueDate", issueDate);
        extracted.put("registrationEntity", registrationEntity);
        extracted.put("confidence", confidence);
        extracted.put("visionModel", visionModel);
        if (visionJson != null) {
            extracted.put("visionJson", visionJson);
        }
        m.put("extracted", extracted);
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public List<String> getValidationMessages() {
        return Collections.unmodifiableList(validationMessages);
    }
    public void setValidationMessages(List<String> validationMessages) {
        this.validationMessages = validationMessages != null ? new ArrayList<>(validationMessages) : new ArrayList<>();
    }
    public String getCurpClave() { return curpClave; }
    public void setCurpClave(String curpClave) { this.curpClave = curpClave; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getIssueDate() { return issueDate; }
    public void setIssueDate(String issueDate) { this.issueDate = issueDate; }
    public String getRegistrationEntity() { return registrationEntity; }
    public void setRegistrationEntity(String registrationEntity) { this.registrationEntity = registrationEntity; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getVisionJson() { return visionJson; }
    public void setVisionJson(String visionJson) { this.visionJson = visionJson; }
}
