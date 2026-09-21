/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.agentic.loan.model;

import java.math.BigDecimal;
import java.util.Map;

public class AssessmentResult {

    private String type;
    private boolean passed;
    private String reason;
    private BigDecimal score;
    private Map<String, Object> details;

    public AssessmentResult() {}

    public static Builder builder() {
        return new Builder();
    }

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

    public static final class Builder {
        private final AssessmentResult target = new AssessmentResult();

        public Builder type(String v) { target.type = v; return this; }
        public Builder passed(boolean v) { target.passed = v; return this; }
        public Builder reason(String v) { target.reason = v; return this; }
        public Builder score(BigDecimal v) { target.score = v; return this; }
        public Builder details(Map<String, Object> v) { target.details = v; return this; }
        public AssessmentResult build() { return target; }
    }
}
