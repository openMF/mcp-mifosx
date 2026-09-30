/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>Public REST surface – intentionally identical to the Temporal-based
 * agentic-banking-temporal API so clients can switch orchestrators without change.
 */
package org.community.mifos.agentic.loan.controller;

import org.community.mifos.agentic.loan.dto.HumanReviewRequest;
import org.community.mifos.agentic.loan.dto.SubmitLoanRequest;
import org.community.mifos.agentic.loan.model.LoanDecision;
import org.community.mifos.agentic.loan.service.LoanOrchestrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanOrchestrationService orchestration;

    public LoanController(LoanOrchestrationService orchestration) {
        this.orchestration = orchestration;
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody SubmitLoanRequest req) {
        Map<String, Object> body = orchestration.submit(req);
        return ResponseEntity.accepted().body(body);
    }

    @GetMapping("/{workflowId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String workflowId) {
        try {
            return ResponseEntity.ok(orchestration.status(workflowId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{workflowId}/summary")
    public ResponseEntity<LoanDecision> summary(@PathVariable String workflowId) {
        try {
            LoanDecision decision = orchestration.summary(workflowId);
            if (decision == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(decision);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{workflowId}/review")
    public ResponseEntity<Map<String, Object>> review(
            @PathVariable String workflowId,
            @Valid @RequestBody HumanReviewRequest req) {
        try {
            return ResponseEntity.ok(orchestration.humanReview(workflowId, req));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "workflowId", workflowId,
                    "error", ex.getMessage()
            ));
        }
    }

    @GetMapping("/{workflowId}/final")
    public ResponseEntity<LoanDecision> finalResult(@PathVariable String workflowId) {
        try {
            LoanDecision decision = orchestration.finalResult(workflowId);
            if (decision == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(decision);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{workflowId}/documents")
    public ResponseEntity<Map<String, String>> documents(
            @PathVariable String workflowId,
            @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> paths = (List<String>) body.get("paths");
            orchestration.documentsUploaded(workflowId, paths);
            return ResponseEntity.ok(Map.of("status", "documents recorded"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }
}
