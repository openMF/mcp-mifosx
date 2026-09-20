package org.community.mifos.agentic.loan.activity;

import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.Map;

@ActivityInterface
public interface FineractActivities {

    @ActivityMethod
    Map<String, Object> createAndApproveLoan(LoanApplication application, LoanDecision decision);
}
