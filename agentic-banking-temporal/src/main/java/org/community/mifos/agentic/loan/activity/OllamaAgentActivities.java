package org.community.mifos.agentic.loan.activity;

import org.community.mifos.agentic.loan.model.LoanDecision;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.Map;

@ActivityInterface
public interface OllamaAgentActivities {

    @ActivityMethod
    LoanDecision aggregateAndDecide(Map<String, Object> context);
}
