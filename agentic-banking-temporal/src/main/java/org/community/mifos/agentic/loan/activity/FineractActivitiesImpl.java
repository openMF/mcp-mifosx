package org.community.mifos.agentic.loan.activity;

import org.community.mifos.agentic.loan.fineract.FineractClient;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("fineractActivities")
@ActivityImpl(taskQueues = "loan-underwriter-queue")
public class FineractActivitiesImpl implements FineractActivities {

    private static final Logger log = LoggerFactory.getLogger(FineractActivitiesImpl.class);

    private final FineractClient fineractClient;

    public FineractActivitiesImpl(FineractClient fineractClient) {
        this.fineractClient = fineractClient;
    }

    @Override
    public Map<String, Object> createAndApproveLoan(LoanApplication application, LoanDecision decision) {
        log.info("Creating loan in Apache Fineract for applicant={}", application.getApplicantId());
        return fineractClient.submitAndApproveLoan(application, decision);
    }
}
