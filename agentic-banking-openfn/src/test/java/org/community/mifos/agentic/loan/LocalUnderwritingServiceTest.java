/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan;

import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import org.community.mifos.agentic.loan.service.LocalUnderwritingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class LocalUnderwritingServiceTest {

    private final LocalUnderwritingService svc = new LocalUnderwritingService();

    @Test
    void underwriteProducesPendingHumanReview() {
        LoanApplication app = LoanApplication.builder()
                .workflowId("loan-test-1")
                .applicantId("APP-1")
                .fullName("Test User")
                .requestedAmount(new BigDecimal("12000"))
                .termMonths(12)
                .build();

        LoanDecision d = svc.underwrite(app);

        assertEquals("loan-test-1", d.getWorkflowId());
        assertNotNull(d.getRecommendation());
        assertEquals("PENDING_HUMAN_REVIEW", d.getFinalStatus());
        assertNotNull(d.getAssessments());
        assertEquals(3, d.getAssessments().size());
    }

    @Test
    void highAmountPrefersRefer() {
        LoanApplication app = LoanApplication.builder()
                .workflowId("loan-test-2")
                .applicantId("APP-2")
                .fullName("High Roller")
                .requestedAmount(new BigDecimal("75000"))
                .termMonths(36)
                .build();

        LoanDecision d = svc.underwrite(app);
        assertEquals("REFER", d.getRecommendation());
    }
}
