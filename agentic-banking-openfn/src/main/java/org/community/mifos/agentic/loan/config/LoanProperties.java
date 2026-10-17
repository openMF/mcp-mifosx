/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "loan")
@Validated
public class LoanProperties {

    private boolean localFallbackEnabled = true;
    private int stateTtlHours = 72;
    // Zero or less would fail every case the moment it starts waiting on OpenFn.
    @Min(1)
    private int openfnTimeoutMinutes = 15;

    public boolean isLocalFallbackEnabled() { return localFallbackEnabled; }
    public void setLocalFallbackEnabled(boolean localFallbackEnabled) {
        this.localFallbackEnabled = localFallbackEnabled;
    }

    public int getStateTtlHours() { return stateTtlHours; }
    public void setStateTtlHours(int stateTtlHours) { this.stateTtlHours = stateTtlHours; }

    /**
     * Minutes a case may wait on OpenFn without any progress before it is marked FAILED
     * ({@code loan.openfn-timeout-minutes}, at least 1).
     */
    public int getOpenfnTimeoutMinutes() { return openfnTimeoutMinutes; }

    /** @param openfnTimeoutMinutes see {@link #getOpenfnTimeoutMinutes()}; must be at least 1 */
    public void setOpenfnTimeoutMinutes(int openfnTimeoutMinutes) {
        this.openfnTimeoutMinutes = openfnTimeoutMinutes;
    }
}
