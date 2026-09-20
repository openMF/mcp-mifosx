package org.community.mifos.agentic.loan.activity;

import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;

@ActivityInterface
public interface FineractActivities {

    /**
     * Create client + loan + approve. When {@code documentResults} contains a valid CURP,
     * client externalId is set to curpClave and the file is uploaded as a loan document.
     */
    @ActivityMethod
    Map<String, Object> createAndApproveLoan(
            LoanApplication application,
            LoanDecision decision,
            List<Map<String, Object>> documentResults);
}
