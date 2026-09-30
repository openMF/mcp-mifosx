/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "loan")
public class LoanProperties {

    private boolean localFallbackEnabled = true;
    private int stateTtlHours = 72;

    public boolean isLocalFallbackEnabled() { return localFallbackEnabled; }
    public void setLocalFallbackEnabled(boolean localFallbackEnabled) {
        this.localFallbackEnabled = localFallbackEnabled;
    }

    public int getStateTtlHours() { return stateTtlHours; }
    public void setStateTtlHours(int stateTtlHours) { this.stateTtlHours = stateTtlHours; }
}
