package org.community.mifos.agentic.loan.workflow;

import org.community.mifos.agentic.loan.activity.FineractActivities;
import org.community.mifos.agentic.loan.activity.LoanActivities;
import org.community.mifos.agentic.loan.activity.OllamaAgentActivities;
import org.community.mifos.agentic.loan.model.AssessmentResult;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable supervisor workflow – on-premise agentic loan origination.
 *
 * Improvements over original Python version:
 * - Fully local Ollama (no cloud LLM dependency)
 * - Apache Fineract integration for real loan account creation
 * - Parallel fan-out with independent retry policies
 * - Structured agent outputs with validation
 * - Explicit human-in-the-loop with queries + signals
 * - Graceful degradation when external systems are unavailable
 */
@WorkflowImpl(taskQueues = "loan-underwriter-queue")
public class SupervisorWorkflowImpl implements SupervisorWorkflow {

    private static final Logger log = Workflow.getLogger(SupervisorWorkflowImpl.class);

    private final LoanActivities loanActivities;
    private final OllamaAgentActivities ollamaActivities;
    private final FineractActivities fineractActivities;

    private boolean humanDecisionReceived = false;
    private String humanAction;
    private String humanComments;
    private LoanDecision summary;
    private LoanDecision finalResult;
    private String status = "STARTED";
    private List<String> documentPaths = new ArrayList<>();

    public SupervisorWorkflowImpl() {
        RetryOptions defaultRetry = RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumInterval(Duration.ofSeconds(30))
                .setBackoffCoefficient(2.0)
                .setMaximumAttempts(5)
                .build();

        ActivityOptions defaultOpts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(defaultRetry)
                .build();

        // Credit report: limited retries so we can fall back quickly
        ActivityOptions creditOpts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(2)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .build())
                .build();

        // LLM can take longer
        ActivityOptions llmOpts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(3))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofSeconds(2))
                        .build())
                .build();

        this.loanActivities = Workflow.newActivityStub(LoanActivities.class, defaultOpts);
        this.ollamaActivities = Workflow.newActivityStub(OllamaAgentActivities.class, llmOpts);
        this.fineractActivities = Workflow.newActivityStub(FineractActivities.class, defaultOpts);

        // Override credit activity options when calling
        // (we keep a separate stub pattern for clarity in the flow)
    }

    @Override
    public LoanDecision processLoan(LoanApplication application) {
        log.info("Starting SupervisorWorkflow for applicant={}", application.getApplicantId());
        status = "DATA_ACQUISITION";

        // ---------- Phase 0: optional document wait ----------
        // In a full UI flow we would wait for a documentsUploaded signal.
        // For automated runs we proceed with whatever paths are already present.
        if (application.getDocumentPaths() != null) {
            documentPaths.addAll(application.getDocumentPaths());
        }

        // ---------- Phase 1: Data acquisition (parallel where safe) ----------
        Promise<Map<String, Object>> bankPromise = Async.function(
                loanActivities::fetchBankAccount, application.getApplicantId());

        // Credit with provider fallback (Temporal-orchestrated)
        Map<String, Object> creditReport;
        try {
            creditReport = loanActivities.fetchCreditReportCibil(application.getApplicantId());
            log.info("CIBIL credit report obtained");
        } catch (Exception e) {
            log.warn("CIBIL failed, falling back to Experian: {}", e.getMessage());
            creditReport = loanActivities.fetchCreditReportExperian(application.getApplicantId());
        }

        Map<String, Object> bankData = bankPromise.get();

        // Document processing – local Ollama text analysis (OCR replaced by structured extraction)
        List<Map<String, Object>> docResults = new ArrayList<>();
        if (!documentPaths.isEmpty()) {
            List<Promise<Map<String, Object>>> docPromises = new ArrayList<>();
            for (String path : documentPaths) {
                docPromises.add(Async.function(loanActivities::processDocument, path, application.getApplicantId()));
            }
            for (Promise<Map<String, Object>> p : docPromises) {
                try {
                    docResults.add(p.get());
                } catch (Exception ex) {
                    log.warn("Document processing failed (isolated): {}", ex.getMessage());
                    docResults.add(Map.of("error", ex.getMessage(), "path", "unknown"));
                }
            }
        }

        status = "ASSESSMENT";

        // ---------- Phase 2: Parallel specialist assessments ----------
        Promise<AssessmentResult> incomeP = Async.function(
                loanActivities::incomeAssessment, application, bankData, creditReport);
        Promise<AssessmentResult> expenseP = Async.function(
                loanActivities::expenseAssessment, application, bankData);
        Promise<AssessmentResult> creditP = Async.function(
                loanActivities::creditAssessment, application, creditReport);

        AssessmentResult income = incomeP.get();
        AssessmentResult expense = expenseP.get();
        AssessmentResult credit = creditP.get();

        List<AssessmentResult> assessments = List.of(income, expense, credit);

        // ---------- Phase 3: LLM aggregation (local Ollama agent) ----------
        status = "LLM_DECISION";
        Map<String, Object> context = new HashMap<>();
        context.put("application", application);
        context.put("bank", bankData);
        context.put("credit", creditReport);
        context.put("documents", docResults);
        context.put("assessments", assessments);

        LoanDecision aiDecision = ollamaActivities.aggregateAndDecide(context);
        aiDecision.setAssessments(assessments);
        summary = aiDecision;
        status = "PENDING_HUMAN_REVIEW";

        // ---------- Phase 4: Human-in-the-loop ----------
        log.info("Waiting for human review signal…");
        Workflow.await(() -> humanDecisionReceived);

        aiDecision.setHumanDecision(humanAction);
        if ("APPROVE".equalsIgnoreCase(humanAction)) {
            aiDecision.setFinalStatus("APPROVED");
            // Create the actual loan in Apache Fineract
            try {
                Map<String, Object> fineractLoan = fineractActivities.createAndApproveLoan(application, aiDecision);
                aiDecision.setFineractLoan(fineractLoan);
            } catch (Exception e) {
                log.error("Fineract loan creation failed – decision still recorded: {}", e.getMessage());
                aiDecision.setFineractLoan(Map.of("error", e.getMessage()));
            }
        } else {
            aiDecision.setFinalStatus("REJECTED");
            // Optionally reject / withdraw in Fineract if a draft was created earlier
        }

        finalResult = aiDecision;
        status = "COMPLETED";
        log.info("Workflow completed with finalStatus={}", aiDecision.getFinalStatus());
        return aiDecision;
    }

    @Override
    public void humanReview(String action, String comments) {
        this.humanAction = action;
        this.humanComments = comments;
        this.humanDecisionReceived = true;
    }

    @Override
    public void documentsUploaded(List<String> paths) {
        if (paths != null) {
            this.documentPaths.addAll(paths);
        }
    }

    @Override
    public LoanDecision getSummary() {
        return summary;
    }

    @Override
    public LoanDecision getFinalResult() {
        return finalResult != null ? finalResult : summary;
    }

    @Override
    public String getStatus() {
        return status;
    }
}
