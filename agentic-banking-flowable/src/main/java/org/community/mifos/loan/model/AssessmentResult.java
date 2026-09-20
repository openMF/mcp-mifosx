package org.community.mifos.loan.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class AssessmentResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String type;
    private boolean passed;
    private String reason;
    private BigDecimal score;
    private Map<String, Object> details;

    public AssessmentResult() {}

    public static Builder builder() { return new Builder(); }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("passed", passed);
        m.put("reason", reason);
        m.put("score", score != null ? score.toPlainString() : null);
        m.put("details", details);
        return m;
    }

    public static AssessmentResult fromMap(Map<String, Object> m) {
        AssessmentResult a = new AssessmentResult();
        a.type = m.get("type") != null ? m.get("type").toString() : null;
        a.passed = Boolean.TRUE.equals(m.get("passed")) || "true".equalsIgnoreCase(String.valueOf(m.get("passed")));
        a.reason = m.get("reason") != null ? m.get("reason").toString() : null;
        if (m.get("score") != null) a.score = new BigDecimal(m.get("score").toString());
        if (m.get("details") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) m.get("details");
            a.details = d;
        }
        return a;
    }

    public static final class Builder {
        private final AssessmentResult t = new AssessmentResult();
        public Builder type(String v) { t.type = v; return this; }
        public Builder passed(boolean v) { t.passed = v; return this; }
        public Builder reason(String v) { t.reason = v; return this; }
        public Builder score(BigDecimal v) { t.score = v; return this; }
        public Builder details(Map<String, Object> v) { t.details = v; return this; }
        public AssessmentResult build() { return t; }
    }
}
