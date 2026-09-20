package org.community.mifos.loan.delegate;

import org.community.mifos.loan.model.AssessmentResult;
import org.community.mifos.loan.model.LoanApplication;
import org.community.mifos.loan.service.LoanDataService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component("assessmentDelegate")
public class AssessmentDelegate implements JavaDelegate {

    private final LoanDataService loanDataService;

    public AssessmentDelegate(LoanDataService loanDataService) {
        this.loanDataService = loanDataService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) {
        Map<String, Object> appMap = (Map<String, Object>) execution.getVariable("loanApplication");
        LoanApplication app = LoanApplication.fromMap(appMap);
        Map<String, Object> bank = (Map<String, Object>) execution.getVariable("bankData");
        Map<String, Object> credit = (Map<String, Object>) execution.getVariable("creditData");

        List<AssessmentResult> assessments = loanDataService.runAllAssessments(app, bank, credit);
        List<Map<String, Object>> asMaps = new ArrayList<>();
        for (AssessmentResult a : assessments) {
            asMaps.add(a.toMap());
        }
        execution.setVariable("assessments", asMaps);
        execution.setVariable("status", "LLM_DECISION");
    }
}
