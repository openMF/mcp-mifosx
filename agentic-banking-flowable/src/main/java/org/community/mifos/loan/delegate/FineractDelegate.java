package org.community.mifos.loan.delegate;

import org.community.mifos.fineract.FineractClient;
import org.community.mifos.loan.model.LoanApplication;
import org.community.mifos.loan.model.LoanDecision;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component("fineractDelegate")
public class FineractDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(FineractDelegate.class);

    private final FineractClient fineractClient;

    public FineractDelegate(FineractClient fineractClient) {
        this.fineractClient = fineractClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) {
        Map<String, Object> appMap = (Map<String, Object>) execution.getVariable("loanApplication");
        LoanApplication app = LoanApplication.fromMap(appMap);
        Map<String, Object> decisionMap = (Map<String, Object>) execution.getVariable("aiDecision");
        LoanDecision decision = LoanDecision.fromMap(decisionMap != null ? decisionMap : Map.of());

        log.info("Creating Fineract loan for applicant={}", app.getApplicantId());

        Map<String, Object> fineractResult = fineractClient.submitAndApproveLoan(app, decision);

        Map<String, Object> updated = decisionMap != null ? new HashMap<>(decisionMap) : new HashMap<>();
        updated.put("fineractLoan", fineractResult);
        updated.put("finalStatus", "APPROVED");
        updated.put("humanDecision", "APPROVE");

        execution.setVariable("aiDecision", updated);
        execution.setVariable("fineractLoan", fineractResult);
        execution.setVariable("finalStatus", "APPROVED");
        execution.setVariable("status", "COMPLETED");
    }
}
