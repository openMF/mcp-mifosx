/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan.model;

import java.util.Map;

public class AssessmentResult {

    private String type;       // INCOME | EXPENSE | CREDIT
    private String verdict;    // PASS | FAIL | REFER
    private double score;
    private String rationale;
    private Map<String, Object> details;

    public AssessmentResult() {}

    public AssessmentResult(String type, String verdict, double score, String rationale) {
        this.type = type;
        this.verdict = verdict;
        this.score = score;
        this.rationale = rationale;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
