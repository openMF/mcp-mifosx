/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan.model;

import java.util.List;
import java.util.Map;

public class LoanDecision {

    private String workflowId;
    private String recommendation;   // APPROVE | REJECT | REFER
    private double confidence;
    private String rationale;
    private List<AssessmentResult> assessments;
    private String humanDecision;    // APPROVE | REJECT (from officer)
    private String finalStatus;      // APPROVED | REJECTED | PENDING_HUMAN_REVIEW | ...
    private Map<String, Object> fineractLoan;
    private Map<String, Object> context;
    private String openfnRunId;
    private String openfnWorkOrderId;

    public LoanDecision() {}

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public List<AssessmentResult> getAssessments() { return assessments; }
    public void setAssessments(List<AssessmentResult> assessments) { this.assessments = assessments; }

    public String getHumanDecision() { return humanDecision; }
    public void setHumanDecision(String humanDecision) { this.humanDecision = humanDecision; }

    public String getFinalStatus() { return finalStatus; }
    public void setFinalStatus(String finalStatus) { this.finalStatus = finalStatus; }

    public Map<String, Object> getFineractLoan() { return fineractLoan; }
    public void setFineractLoan(Map<String, Object> fineractLoan) { this.fineractLoan = fineractLoan; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }

    public String getOpenfnRunId() { return openfnRunId; }
    public void setOpenfnRunId(String openfnRunId) { this.openfnRunId = openfnRunId; }

    public String getOpenfnWorkOrderId() { return openfnWorkOrderId; }
    public void setOpenfnWorkOrderId(String openfnWorkOrderId) { this.openfnWorkOrderId = openfnWorkOrderId; }
}
