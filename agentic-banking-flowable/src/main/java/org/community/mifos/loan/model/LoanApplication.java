package org.community.mifos.loan.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoanApplication implements Serializable {

    private static final long serialVersionUID = 1L;

    private String workflowId;
    @NotBlank private String applicantId;
    @NotBlank private String fullName;
    private String email;
    private String phone;
    @NotNull @Min(1000) private BigDecimal requestedAmount;
    private Integer termMonths;
    private String purpose;
    private LocalDate applicationDate;
    private List<String> documentPaths;
    private Map<String, Object> metadata;

    public LoanApplication() {}

    public static Builder builder() { return new Builder(); }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }
    public String getApplicantId() { return applicantId; }
    public void setApplicantId(String applicantId) { this.applicantId = applicantId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public void setRequestedAmount(BigDecimal requestedAmount) { this.requestedAmount = requestedAmount; }
    public Integer getTermMonths() { return termMonths; }
    public void setTermMonths(Integer termMonths) { this.termMonths = termMonths; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public LocalDate getApplicationDate() { return applicationDate; }
    public void setApplicationDate(LocalDate applicationDate) { this.applicationDate = applicationDate; }
    public List<String> getDocumentPaths() { return documentPaths; }
    public void setDocumentPaths(List<String> documentPaths) { this.documentPaths = documentPaths; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    /** Flatten to Map for Flowable variables (always serializable). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("workflowId", workflowId);
        m.put("applicantId", applicantId);
        m.put("fullName", fullName);
        m.put("email", email);
        m.put("phone", phone);
        m.put("requestedAmount", requestedAmount != null ? requestedAmount.toPlainString() : null);
        m.put("termMonths", termMonths);
        m.put("purpose", purpose);
        m.put("applicationDate", applicationDate != null ? applicationDate.toString() : null);
        m.put("documentPaths", documentPaths);
        m.put("metadata", metadata);
        return m;
    }

    public static LoanApplication fromMap(Map<String, Object> m) {
        if (m == null) return null;
        LoanApplication a = new LoanApplication();
        a.workflowId = str(m.get("workflowId"));
        a.applicantId = str(m.get("applicantId"));
        a.fullName = str(m.get("fullName"));
        a.email = str(m.get("email"));
        a.phone = str(m.get("phone"));
        if (m.get("requestedAmount") != null) {
            a.requestedAmount = new BigDecimal(m.get("requestedAmount").toString());
        }
        if (m.get("termMonths") instanceof Number n) a.termMonths = n.intValue();
        a.purpose = str(m.get("purpose"));
        if (m.get("applicationDate") != null) {
            a.applicationDate = LocalDate.parse(m.get("applicationDate").toString());
        }
        @SuppressWarnings("unchecked")
        List<String> paths = (List<String>) m.get("documentPaths");
        a.documentPaths = paths;
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) m.get("metadata");
        a.metadata = meta;
        return a;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    public static final class Builder {
        private final LoanApplication t = new LoanApplication();
        public Builder workflowId(String v) { t.workflowId = v; return this; }
        public Builder applicantId(String v) { t.applicantId = v; return this; }
        public Builder fullName(String v) { t.fullName = v; return this; }
        public Builder email(String v) { t.email = v; return this; }
        public Builder phone(String v) { t.phone = v; return this; }
        public Builder requestedAmount(BigDecimal v) { t.requestedAmount = v; return this; }
        public Builder termMonths(Integer v) { t.termMonths = v; return this; }
        public Builder purpose(String v) { t.purpose = v; return this; }
        public Builder applicationDate(LocalDate v) { t.applicationDate = v; return this; }
        public Builder documentPaths(List<String> v) { t.documentPaths = v; return this; }
        public Builder metadata(Map<String, Object> v) { t.metadata = v; return this; }
        public LoanApplication build() { return t; }
    }
}
