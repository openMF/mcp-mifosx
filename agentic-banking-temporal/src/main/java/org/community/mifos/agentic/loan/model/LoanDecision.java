package org.community.mifos.agentic.loan.model;

import java.util.List;
import java.util.Map;

public class LoanDecision {

    public enum Recommendation { APPROVE, REJECT, REFER }

    private Recommendation recommendation;
    private String summary;
    private String riskLevel;
    private List<AssessmentResult> assessments;
    private Map<String, Object> fineractLoan;
    private String humanDecision;
    private String finalStatus;
    /** Full LLM response text for audit / Fineract loan note. */
    private String llmThinking;
    private String llmModel;

    public LoanDecision() {}

    public static Builder builder() {
        return new Builder();
    }

    public Recommendation getRecommendation() { return recommendation; }
    public void setRecommendation(Recommendation recommendation) { this.recommendation = recommendation; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public List<AssessmentResult> getAssessments() { return assessments; }
    public void setAssessments(List<AssessmentResult> assessments) { this.assessments = assessments; }

    public Map<String, Object> getFineractLoan() { return fineractLoan; }
    public void setFineractLoan(Map<String, Object> fineractLoan) { this.fineractLoan = fineractLoan; }

    public String getHumanDecision() { return humanDecision; }
    public void setHumanDecision(String humanDecision) { this.humanDecision = humanDecision; }

    public String getFinalStatus() { return finalStatus; }
    public void setFinalStatus(String finalStatus) { this.finalStatus = finalStatus; }

    public String getLlmThinking() { return llmThinking; }
    public void setLlmThinking(String llmThinking) { this.llmThinking = llmThinking; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public static final class Builder {
        private final LoanDecision target = new LoanDecision();

        public Builder recommendation(Recommendation v) { target.recommendation = v; return this; }
        public Builder summary(String v) { target.summary = v; return this; }
        public Builder riskLevel(String v) { target.riskLevel = v; return this; }
        public Builder assessments(List<AssessmentResult> v) { target.assessments = v; return this; }
        public Builder fineractLoan(Map<String, Object> v) { target.fineractLoan = v; return this; }
        public Builder humanDecision(String v) { target.humanDecision = v; return this; }
        public Builder finalStatus(String v) { target.finalStatus = v; return this; }
        public Builder llmThinking(String v) { target.llmThinking = v; return this; }
        public Builder llmModel(String v) { target.llmModel = v; return this; }
        public LoanDecision build() { return target; }
    }
}
