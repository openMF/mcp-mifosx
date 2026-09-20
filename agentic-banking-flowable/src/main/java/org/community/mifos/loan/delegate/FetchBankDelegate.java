package org.community.mifos.loan.delegate;

import org.community.mifos.loan.service.LoanDataService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("fetchBankDelegate")
public class FetchBankDelegate implements JavaDelegate {

    private final LoanDataService loanDataService;

    public FetchBankDelegate(LoanDataService loanDataService) {
        this.loanDataService = loanDataService;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String applicantId = (String) execution.getVariable("applicantId");
        Map<String, Object> bank = loanDataService.fetchBankAccount(applicantId);
        execution.setVariable("bankData", bank);
        execution.setVariable("status", "CREDIT_FETCH");
    }
}
