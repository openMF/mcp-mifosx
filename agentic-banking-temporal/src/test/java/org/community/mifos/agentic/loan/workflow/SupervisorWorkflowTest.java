package org.community.mifos.agentic.loan.workflow;

import org.community.mifos.agentic.loan.workflow.SupervisorWorkflowImpl;
import org.community.mifos.agentic.loan.workflow.SupervisorWorkflow;
import org.community.mifos.agentic.loan.activity.FineractActivities;
import org.community.mifos.agentic.loan.activity.LoanActivities;
import org.community.mifos.agentic.loan.activity.OllamaAgentActivities;
import org.community.mifos.agentic.loan.model.AssessmentResult;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit / integration style test using Temporal TestWorkflowEnvironment.
 * Activities are mocked so the test runs fully offline (no Ollama / Fineract required).
 */
class SupervisorWorkflowTest {

    @RegisterExtension
    public static final TestWorkflowExtension testWorkflowExtension =
            TestWorkflowExtension.newBuilder()
                    .setWorkflowTypes(SupervisorWorkflowImpl.class)
                    .setDoNotStart(true)
                    .build();

    @Test
    void fullHappyPathApprove(TestWorkflowEnvironment testEnv, Worker worker, WorkflowClient client) {
        // Mock activities
        LoanActivities loanActs = mock(LoanActivities.class);
        OllamaAgentActivities ollamaActs = mock(OllamaAgentActivities.class);
        FineractActivities fineractActs = mock(FineractActivities.class);

        when(loanActs.fetchBankAccount(anyString())).thenReturn(Map.of(
                "monthlyIncome", 6000, "monthlyExpenses", 2500, "balance", 20000));
        when(loanActs.fetchCreditReportCibil(anyString())).thenReturn(Map.of(
                "provider", "CIBIL", "score", 740));
        when(loanActs.incomeAssessment(any(), any(), any())).thenReturn(
                AssessmentResult.builder().type("income").passed(true).reason("ok").build());
        when(loanActs.expenseAssessment(any(), any())).thenReturn(
                AssessmentResult.builder().type("expense").passed(true).reason("ok").build());
        when(loanActs.creditAssessment(any(), any())).thenReturn(
                AssessmentResult.builder().type("credit").passed(true).reason("ok").build());

        when(ollamaActs.aggregateAndDecide(any())).thenReturn(LoanDecision.builder()
                .recommendation(LoanDecision.Recommendation.APPROVE)
                .summary("Strong applicant")
                .riskLevel("LOW")
                .finalStatus("PENDING_REVIEW")
                .build());

        when(fineractActs.createAndApproveLoan(any(), any())).thenReturn(Map.of(
                "loanId", 12345L, "status", "APPROVED_IN_FINERACT"));

        worker.registerActivitiesImplementations(loanActs, ollamaActs, fineractActs);
        testEnv.start();

        SupervisorWorkflow workflow = client.newWorkflowStub(
                SupervisorWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .setWorkflowId("test-loan-1")
                        .build());

        LoanApplication app = LoanApplication.builder()
                .applicantId("APP-001")
                .fullName("Jane Doe")
                .requestedAmount(new BigDecimal("15000"))
                .termMonths(24)
                .build();

        // Start async so we can signal
        WorkflowClient.start(workflow::processLoan, app);

        // Wait until summary is available
        testEnv.sleep(java.time.Duration.ofSeconds(1));
        LoanDecision summary = workflow.getSummary();
        assertNotNull(summary);
        assertEquals(LoanDecision.Recommendation.APPROVE, summary.getRecommendation());

        // Human approve
        workflow.humanReview("APPROVE", "Looks good");

        // Wait for completion
        LoanDecision finalResult = workflow.getFinalResult();
        assertEquals("APPROVED", finalResult.getFinalStatus());
        assertNotNull(finalResult.getFineractLoan());
        assertEquals(12345L, ((Number) finalResult.getFineractLoan().get("loanId")).longValue());
    }

    @Test
    void creditFallbackPath(TestWorkflowEnvironment testEnv, Worker worker, WorkflowClient client) {
        LoanActivities loanActs = mock(LoanActivities.class);
        OllamaAgentActivities ollamaActs = mock(OllamaAgentActivities.class);
        FineractActivities fineractActs = mock(FineractActivities.class);

        when(loanActs.fetchBankAccount(anyString())).thenReturn(Map.of("monthlyIncome", 4000, "monthlyExpenses", 2000));
        // CIBIL fails → Experian succeeds
        when(loanActs.fetchCreditReportCibil(anyString()))
                .thenThrow(new RuntimeException("CIBIL down"));
        when(loanActs.fetchCreditReportExperian(anyString()))
                .thenReturn(Map.of("provider", "Experian", "score", 680));

        when(loanActs.incomeAssessment(any(), any(), any())).thenReturn(
                AssessmentResult.builder().type("income").passed(true).build());
        when(loanActs.expenseAssessment(any(), any())).thenReturn(
                AssessmentResult.builder().type("expense").passed(true).build());
        when(loanActs.creditAssessment(any(), any())).thenReturn(
                AssessmentResult.builder().type("credit").passed(true).build());

        when(ollamaActs.aggregateAndDecide(any())).thenReturn(LoanDecision.builder()
                .recommendation(LoanDecision.Recommendation.APPROVE)
                .summary("Fallback credit used")
                .riskLevel("MEDIUM")
                .build());

        worker.registerActivitiesImplementations(loanActs, ollamaActs, fineractActs);
        testEnv.start();

        SupervisorWorkflow workflow = client.newWorkflowStub(
                SupervisorWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .setWorkflowId("test-loan-fallback")
                        .build());

        LoanApplication app = LoanApplication.builder()
                .applicantId("APP-002")
                .fullName("John Smith")
                .requestedAmount(new BigDecimal("8000"))
                .build();

        WorkflowClient.start(workflow::processLoan, app);
        testEnv.sleep(java.time.Duration.ofSeconds(1));

        workflow.humanReview("APPROVE", null);
        LoanDecision result = workflow.getFinalResult();
        assertEquals("APPROVED", result.getFinalStatus());
    }
}
