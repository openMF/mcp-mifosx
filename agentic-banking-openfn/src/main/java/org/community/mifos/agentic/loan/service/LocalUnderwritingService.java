/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>Local fallback when OpenFn Lightning is unreachable.
 * Mirrors the specialist assessments + heuristic decision that the OpenFn jobs perform.
 */
package org.community.mifos.agentic.loan.service;

import org.community.mifos.agentic.loan.model.AssessmentResult;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class LocalUnderwritingService {

    private static final Logger log = LoggerFactory.getLogger(LocalUnderwritingService.class);

    public LoanDecision underwrite(LoanApplication app) {
        log.info("Running local underwriting fallback for {}", app.getWorkflowId());

        AssessmentResult income = assessIncome(app);
        AssessmentResult expense = assessExpense(app);
        AssessmentResult credit = assessCredit(app);

        List<AssessmentResult> assessments = List.of(income, expense, credit);

        long fails = assessments.stream().filter(a -> "FAIL".equals(a.getVerdict())).count();
        long refers = assessments.stream().filter(a -> "REFER".equals(a.getVerdict())).count();

        LoanDecision decision = new LoanDecision();
        decision.setWorkflowId(app.getWorkflowId());
        decision.setAssessments(assessments);
        decision.setContext(Map.of(
                "source", "local-fallback",
                "requestedAmount", app.getRequestedAmount()
        ));

        if (fails > 0) {
            decision.setRecommendation("REJECT");
            decision.setConfidence(0.7);
            decision.setRationale("One or more specialist assessments failed (local heuristic).");
        } else if (refers > 0 || app.getRequestedAmount().compareTo(BigDecimal.valueOf(50_000)) > 0) {
            decision.setRecommendation("REFER");
            decision.setConfidence(0.6);
            decision.setRationale("Borderline case or high amount – requires human review.");
        } else {
            decision.setRecommendation("APPROVE");
            decision.setConfidence(0.75);
            decision.setRationale("All specialist assessments passed (local heuristic).");
        }

        decision.setFinalStatus("PENDING_HUMAN_REVIEW");
        return decision;
    }

    private AssessmentResult assessIncome(LoanApplication app) {
        // Stub: treat requested amount as proxy – real impl would call bank API
        double score = app.getRequestedAmount().doubleValue() < 25_000 ? 0.85 : 0.55;
        return new AssessmentResult("INCOME", score >= 0.7 ? "PASS" : "REFER", score,
                "Local income proxy based on requested amount");
    }

    private AssessmentResult assessExpense(LoanApplication app) {
        double score = 0.8;
        return new AssessmentResult("EXPENSE", "PASS", score, "Local expense stub – assume healthy ratio");
    }

    private AssessmentResult assessCredit(LoanApplication app) {
        // Simulate CIBIL → Experian style outcome
        double score = 0.72;
        return new AssessmentResult("CREDIT", "PASS", score, "Local credit stub – synthetic score 720");
    }
}
