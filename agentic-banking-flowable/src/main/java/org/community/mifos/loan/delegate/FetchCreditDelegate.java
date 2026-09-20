package org.community.mifos.loan.delegate;

import org.community.mifos.loan.service.LoanDataService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("fetchCreditDelegate")
public class FetchCreditDelegate implements JavaDelegate {

    private final LoanDataService loanDataService;

    public FetchCreditDelegate(LoanDataService loanDataService) {
        this.loanDataService = loanDataService;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String applicantId = (String) execution.getVariable("applicantId");
        Map<String, Object> credit = loanDataService.fetchCreditReport(applicantId);
        execution.setVariable("creditData", credit);
        execution.setVariable("status", "ASSESSMENT");
    }
}
