package org.community.mifos.loan.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoanDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Recommendation { APPROVE, REJECT, REFER }

    private Recommendation recommendation;
    private String summary;
    private String riskLevel;
    private List<AssessmentResult> assessments;
    private Map<String, Object> fineractLoan;
    private String humanDecision;
    private String finalStatus;

    public LoanDecision() {}

    public static Builder builder() { return new Builder(); }

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

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("recommendation", recommendation != null ? recommendation.name() : null);
        m.put("summary", summary);
        m.put("riskLevel", riskLevel);
        m.put("humanDecision", humanDecision);
        m.put("finalStatus", finalStatus);
        m.put("fineractLoan", fineractLoan);
        if (assessments != null) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (AssessmentResult a : assessments) {
                list.add(a.toMap());
            }
            m.put("assessments", list);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    public static LoanDecision fromMap(Map<String, Object> m) {
        if (m == null) return null;
        LoanDecision d = new LoanDecision();
        if (m.get("recommendation") != null) {
            d.recommendation = Recommendation.valueOf(m.get("recommendation").toString());
        }
        d.summary = m.get("summary") != null ? m.get("summary").toString() : null;
        d.riskLevel = m.get("riskLevel") != null ? m.get("riskLevel").toString() : null;
        d.humanDecision = m.get("humanDecision") != null ? m.get("humanDecision").toString() : null;
        d.finalStatus = m.get("finalStatus") != null ? m.get("finalStatus").toString() : null;
        if (m.get("fineractLoan") instanceof Map) {
            d.fineractLoan = (Map<String, Object>) m.get("fineractLoan");
        }
        if (m.get("assessments") instanceof List<?> list) {
            List<AssessmentResult> results = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map) {
                    results.add(AssessmentResult.fromMap((Map<String, Object>) o));
                }
            }
            d.assessments = results;
        }
        return d;
    }

    public static final class Builder {
        private final LoanDecision t = new LoanDecision();
        public Builder recommendation(Recommendation v) { t.recommendation = v; return this; }
        public Builder summary(String v) { t.summary = v; return this; }
        public Builder riskLevel(String v) { t.riskLevel = v; return this; }
        public Builder assessments(List<AssessmentResult> v) { t.assessments = v; return this; }
        public Builder fineractLoan(Map<String, Object> v) { t.fineractLoan = v; return this; }
        public Builder humanDecision(String v) { t.humanDecision = v; return this; }
        public Builder finalStatus(String v) { t.finalStatus = v; return this; }
        public LoanDecision build() { return t; }
    }
}
