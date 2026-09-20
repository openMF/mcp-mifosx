package org.community.mifos.agentic.loan.controller;

import org.community.mifos.agentic.loan.dto.HumanReviewRequest;
import org.community.mifos.agentic.loan.dto.SubmitLoanRequest;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import org.community.mifos.agentic.loan.workflow.SupervisorWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final WorkflowClient workflowClient;

    public LoanController(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody SubmitLoanRequest req) {
        String workflowId = "loan-" + req.getApplicantId() + "-" + UUID.randomUUID().toString().substring(0, 8);

        LoanApplication app = LoanApplication.builder()
                .workflowId(workflowId)
                .applicantId(req.getApplicantId())
                .fullName(req.getFullName())
                .email(req.getEmail())
                .phone(req.getPhone())
                .requestedAmount(req.getRequestedAmount())
                .termMonths(req.getTermMonths())
                .purpose(req.getPurpose())
                .applicationDate(LocalDate.now())
                .documentPaths(req.getDocumentPaths())
                .metadata(req.getMetadata())
                .build();

        SupervisorWorkflow workflow = workflowClient.newWorkflowStub(
                SupervisorWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue("loan-underwriter-queue")
                        .build());

        // Start async – workflow will pause for human review
        WorkflowClient.start(workflow::processLoan, app);

        return ResponseEntity.accepted().body(Map.of(
                "workflowId", workflowId,
                "status", "STARTED",
                "message", "Loan workflow started. Poll /api/loans/{id}/summary then POST review."
        ));
    }

    @GetMapping("/{workflowId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String workflowId) {
        SupervisorWorkflow stub = workflowClient.newWorkflowStub(SupervisorWorkflow.class, workflowId);
        return ResponseEntity.ok(Map.of(
                "workflowId", workflowId,
                "status", stub.getStatus()
        ));
    }

    @GetMapping("/{workflowId}/summary")
    public ResponseEntity<LoanDecision> summary(@PathVariable String workflowId) {
        SupervisorWorkflow stub = workflowClient.newWorkflowStub(SupervisorWorkflow.class, workflowId);
        LoanDecision decision = stub.getSummary();
        if (decision == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(decision);
    }

    @PostMapping("/{workflowId}/review")
    public ResponseEntity<Map<String, Object>> review(
            @PathVariable String workflowId,
            @Valid @RequestBody HumanReviewRequest req) {

        SupervisorWorkflow stub = workflowClient.newWorkflowStub(SupervisorWorkflow.class, workflowId);
        stub.humanReview(req.getAction().toUpperCase(), req.getComments());

        return ResponseEntity.ok(Map.of(
                "workflowId", workflowId,
                "action", req.getAction(),
                "message", "Human review signal sent"
        ));
    }

    @GetMapping("/{workflowId}/final")
    public ResponseEntity<LoanDecision> finalResult(@PathVariable String workflowId) {
        SupervisorWorkflow stub = workflowClient.newWorkflowStub(SupervisorWorkflow.class, workflowId);
        LoanDecision decision = stub.getFinalResult();
        if (decision == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(decision);
    }

    /** Convenience: signal documents uploaded (optional) */
    @PostMapping("/{workflowId}/documents")
    public ResponseEntity<Map<String, String>> documents(
            @PathVariable String workflowId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var paths = (java.util.List<String>) body.get("paths");
        SupervisorWorkflow stub = workflowClient.newWorkflowStub(SupervisorWorkflow.class, workflowId);
        stub.documentsUploaded(paths);
        return ResponseEntity.ok(Map.of("status", "documents signalled"));
    }
}
