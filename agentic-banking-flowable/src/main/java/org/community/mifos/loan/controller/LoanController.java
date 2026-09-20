package org.community.mifos.loan.controller;

import org.community.mifos.loan.dto.HumanReviewRequest;
import org.community.mifos.loan.dto.SubmitLoanRequest;
import org.community.mifos.loan.model.LoanApplication;
import org.community.mifos.loan.model.LoanDecision;
import org.community.mifos.loan.service.LoanProcessService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanProcessService processService;

    public LoanController(LoanProcessService processService) {
        this.processService = processService;
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody SubmitLoanRequest req) {
        String businessKey = "loan-" + req.getApplicantId() + "-" + UUID.randomUUID().toString().substring(0, 8);

        LoanApplication app = LoanApplication.builder()
                .workflowId(businessKey)
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

        String processInstanceId = processService.startProcess(app);

        return ResponseEntity.accepted().body(Map.of(
                "workflowId", processInstanceId,
                "businessKey", businessKey,
                "status", "STARTED",
                "message", "Flowable process started. Poll /status then POST /review when PENDING_HUMAN_REVIEW."
        ));
    }

    @GetMapping("/{processInstanceId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String processInstanceId) {
        return ResponseEntity.ok(Map.of(
                "workflowId", processInstanceId,
                "status", processService.getStatus(processInstanceId)
        ));
    }

    @GetMapping("/{processInstanceId}/summary")
    public ResponseEntity<LoanDecision> summary(@PathVariable String processInstanceId) {
        LoanDecision d = processService.getSummary(processInstanceId);
        if (d == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(d);
    }

    @PostMapping("/{processInstanceId}/review")
    public ResponseEntity<Map<String, Object>> review(
            @PathVariable String processInstanceId,
            @Valid @RequestBody HumanReviewRequest req) {
        processService.completeHumanReview(processInstanceId, req.getAction(), req.getComments());
        return ResponseEntity.ok(Map.of(
                "workflowId", processInstanceId,
                "action", req.getAction(),
                "message", "Human review completed"
        ));
    }

    @GetMapping("/{processInstanceId}/final")
    public ResponseEntity<LoanDecision> finalResult(@PathVariable String processInstanceId) {
        LoanDecision d = processService.getFinalResult(processInstanceId);
        if (d == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(d);
    }

    @GetMapping("/tasks")
    public ResponseEntity<?> openTasks() {
        return ResponseEntity.ok(processService.listOpenReviewTasks().stream()
                .map(t -> Map.of(
                        "taskId", t.getId(),
                        "processInstanceId", t.getProcessInstanceId(),
                        "name", t.getName(),
                        "createTime", t.getCreateTime() != null ? t.getCreateTime().toString() : ""
                ))
                .toList());
    }
}
