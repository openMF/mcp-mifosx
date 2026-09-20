package org.community.mifos.loan.delegate;

import org.community.mifos.loan.model.LoanDecision;
import org.community.mifos.loan.service.OllamaDecisionService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component("ollamaDecisionDelegate")
public class OllamaDecisionDelegate implements JavaDelegate {

    private final OllamaDecisionService ollamaDecisionService;

    public OllamaDecisionDelegate(OllamaDecisionService ollamaDecisionService) {
        this.ollamaDecisionService = ollamaDecisionService;
    }

    @Override
    public void execute(DelegateExecution execution) {
        Map<String, Object> context = new HashMap<>();
        context.put("application", execution.getVariable("loanApplication"));
        context.put("bank", execution.getVariable("bankData"));
        context.put("credit", execution.getVariable("creditData"));
        context.put("assessments", execution.getVariable("assessments"));

        LoanDecision decision = ollamaDecisionService.decide(context);
        execution.setVariable("aiDecision", decision.toMap());
        execution.setVariable("recommendation",
                decision.getRecommendation() != null ? decision.getRecommendation().name() : "REFER");
        execution.setVariable("status", "PENDING_HUMAN_REVIEW");
    }
}
