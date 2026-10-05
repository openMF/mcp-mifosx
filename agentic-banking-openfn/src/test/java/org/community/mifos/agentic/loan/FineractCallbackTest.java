/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan;

import org.community.mifos.agentic.loan.config.LoanProperties;
import org.community.mifos.agentic.loan.config.OpenFnProperties;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanCaseState;
import org.community.mifos.agentic.loan.openfn.OpenFnClient;
import org.community.mifos.agentic.loan.service.LoanCaseStore;
import org.community.mifos.agentic.loan.service.LoanOrchestrationService;
import org.community.mifos.agentic.loan.service.LocalUnderwritingService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FineractCallbackTest {

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
        LoanCaseState state = new LoanCaseState(workflowId, app);
        state.setStatus(LoanCaseState.Status.WRITING_TO_FINERACT);
        store.put(state);
    }

    @Test
    void fineractErrorMarksCaseFailed() {
        seedCase("loan-fx-1");

        svc.onFineractComplete(Map.of(
                "workflowId", "loan-fx-1",
                "fineractLoan", Map.of(
                        "status", "FINERACT_ERROR",
                        "error", "POST to /loans returned 400: Bad Request")));

        Map<String, Object> status = svc.status("loan-fx-1");
        assertEquals("FAILED", status.get("status"));
        assertTrue(String.valueOf(status.get("error")).contains("returned 400"));
        assertEquals("FINERACT_ERROR", svc.finalResult("loan-fx-1").getFinalStatus());
    }

    @Test
    void successfulWriteBackMarksCaseCompleted() {
        seedCase("loan-fx-2");

        svc.onFineractComplete(Map.of(
                "workflowId", "loan-fx-2",
                "fineractLoan", Map.of("status", "APPROVED_IN_FINERACT", "loanId", 2, "clientId", 2)));

        assertEquals("COMPLETED", svc.status("loan-fx-2").get("status"));
        assertEquals("APPROVED", svc.finalResult("loan-fx-2").getFinalStatus());
    }
}
