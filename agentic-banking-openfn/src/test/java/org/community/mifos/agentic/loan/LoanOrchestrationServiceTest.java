/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan;

import org.community.mifos.agentic.loan.config.LoanProperties;
import org.community.mifos.agentic.loan.config.OpenFnProperties;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanCaseState;
import org.community.mifos.agentic.loan.model.LoanDecision;
import org.community.mifos.agentic.loan.openfn.OpenFnClient;
import org.community.mifos.agentic.loan.service.LoanCaseStore;
import org.community.mifos.agentic.loan.service.LoanOrchestrationService;
import org.community.mifos.agentic.loan.service.LocalUnderwritingService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoanOrchestrationServiceTest {

    private final LoanProperties loanProps = new LoanProperties();
    private final OpenFnProperties openFnProps = new OpenFnProperties();
    private final LoanCaseStore store = new LoanCaseStore(loanProps);
    private final LoanOrchestrationService svc = new LoanOrchestrationService(
            store,
            new OpenFnClient(openFnProps, RestClient.builder()),
            new LocalUnderwritingService(),
            loanProps,
            openFnProps);

    private void seedCase(String workflowId) {
        LoanApplication app = LoanApplication.builder()
                .workflowId(workflowId)
                .applicantId("APP-1")
                .fullName("Test User")
                .requestedAmount(new BigDecimal("20000"))
                .termMonths(12)
                .build();
        store.put(new LoanCaseState(workflowId, app));
    }

    @Test
    void underwritingCompleteKeepsAssessmentsAlongsideContext() {
        seedCase("loan-cb-1");

        svc.onUnderwritingComplete(Map.of(
                "workflowId", "loan-cb-1",
                "recommendation", "APPROVE",
                "confidence", 0.78,
                "rationale", "All specialist assessments passed.",
                "assessments", List.of(
                        Map.of("type", "INCOME", "verdict", "PASS", "score", 0.85, "rationale", "ok"),
                        Map.of("type", "EXPENSE", "verdict", "PASS", "score", 0.8, "rationale", "ok"),
                        Map.of("type", "CREDIT", "verdict", "PASS", "score", 0.8, "rationale", "ok")),
                "context", Map.of("bank", Map.of("provider", "mock-bank"))));

        LoanDecision d = svc.summary("loan-cb-1");
        assertNotNull(d.getAssessments());
        assertEquals(3, d.getAssessments().size());
        assertEquals("CREDIT", d.getAssessments().get(2).getType());
        assertEquals(0.85, d.getAssessments().get(0).getScore());
        assertTrue(d.getContext().containsKey("bank"));
    }

    @Test
    void callbackWithoutWorkflowIdIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.onStatusUpdate(Map.of("phase", "GATHERING_DATA")));
        assertEquals("workflowId is required", ex.getMessage());
    }
}
