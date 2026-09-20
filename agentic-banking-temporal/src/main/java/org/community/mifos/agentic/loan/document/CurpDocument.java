package org.community.mifos.agentic.loan.document;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Structured data extracted from a Mexican CURP constancia. */
public class CurpDocument implements Serializable {

    private static final long serialVersionUID = 1L;

    private String curpClave;
    private String fullName;
    private String registrationEntity;
    private LocalDate issueDate;
    private boolean civilRegistryVerified;
    private String ocrText;
    private String visionJson;
    private double confidence;
    private final List<String> validationMessages = new ArrayList<>();
    private boolean nameMatches;
    private boolean registryOk;
    private boolean issueDateOk;
    private boolean overallValid;

    public String getCurpClave() { return curpClave; }
    public void setCurpClave(String curpClave) { this.curpClave = curpClave; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getRegistrationEntity() { return registrationEntity; }
    public void setRegistrationEntity(String registrationEntity) { this.registrationEntity = registrationEntity; }
    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }
    public boolean isCivilRegistryVerified() { return civilRegistryVerified; }
    public void setCivilRegistryVerified(boolean civilRegistryVerified) { this.civilRegistryVerified = civilRegistryVerified; }
    public String getOcrText() { return ocrText; }
    public void setOcrText(String ocrText) { this.ocrText = ocrText; }
    public String getVisionJson() { return visionJson; }
    public void setVisionJson(String visionJson) { this.visionJson = visionJson; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public List<String> getValidationMessages() { return validationMessages; }
    public void addMessage(String m) { validationMessages.add(m); }
    public boolean isNameMatches() { return nameMatches; }
    public void setNameMatches(boolean nameMatches) { this.nameMatches = nameMatches; }
    public boolean isRegistryOk() { return registryOk; }
    public void setRegistryOk(boolean registryOk) { this.registryOk = registryOk; }
    public boolean isIssueDateOk() { return issueDateOk; }
    public void setIssueDateOk(boolean issueDateOk) { this.issueDateOk = issueDateOk; }
    public boolean isOverallValid() { return overallValid; }
    public void setOverallValid(boolean overallValid) { this.overallValid = overallValid; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "CURP");
        m.put("curpClave", curpClave);
        m.put("fullName", fullName);
        m.put("registrationEntity", registrationEntity);
        m.put("issueDate", issueDate != null ? issueDate.toString() : null);
        m.put("civilRegistryVerified", civilRegistryVerified);
        m.put("confidence", confidence);
        m.put("nameMatches", nameMatches);
        m.put("registryOk", registryOk);
        m.put("issueDateOk", issueDateOk);
        m.put("overallValid", overallValid);
        m.put("validationMessages", new ArrayList<>(validationMessages));
        return m;
    }
}
