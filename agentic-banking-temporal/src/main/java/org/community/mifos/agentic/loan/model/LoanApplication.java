package org.community.mifos.agentic.loan.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class LoanApplication {

    private String workflowId;

    @NotBlank
    private String applicantId;

    @NotBlank
    private String fullName;

    private String email;
    private String phone;

    @NotNull
    @Min(1000)
    private BigDecimal requestedAmount;

    private Integer termMonths;
    private String purpose;
    private LocalDate applicationDate;
    private List<String> documentPaths;
    private Map<String, Object> metadata;

    public LoanApplication() {}

    public static Builder builder() {
        return new Builder();
    }

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

    public static final class Builder {
        private final LoanApplication target = new LoanApplication();

        public Builder workflowId(String v) { target.workflowId = v; return this; }
        public Builder applicantId(String v) { target.applicantId = v; return this; }
        public Builder fullName(String v) { target.fullName = v; return this; }
        public Builder email(String v) { target.email = v; return this; }
        public Builder phone(String v) { target.phone = v; return this; }
        public Builder requestedAmount(BigDecimal v) { target.requestedAmount = v; return this; }
        public Builder termMonths(Integer v) { target.termMonths = v; return this; }
        public Builder purpose(String v) { target.purpose = v; return this; }
        public Builder applicationDate(LocalDate v) { target.applicationDate = v; return this; }
        public Builder documentPaths(List<String> v) { target.documentPaths = v; return this; }
        public Builder metadata(Map<String, Object> v) { target.metadata = v; return this; }
        public LoanApplication build() { return target; }
    }
}
