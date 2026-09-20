package org.community.mifos.agentic.loan.controller;

import org.community.mifos.agentic.loan.agent.LoanOfficerAgent;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/officer")
public class LoanOfficerController {

    private final LoanOfficerAgent agent;

    public LoanOfficerController(LoanOfficerAgent agent) {
        this.agent = agent;
    }

    @GetMapping("/portfolio")
    public ResponseEntity<Map<String, Object>> portfolio(
            @RequestParam(required = false) String externalId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(agent.portfolio(externalId, clientId, limit));
    }

    @GetMapping("/products/active")
    public ResponseEntity<Map<String, Object>> activeProduct(
            @RequestParam(required = false) Integer productId) {
        LoanApplication dummy = LoanApplication.builder()
                .requestedAmount(BigDecimal.valueOf(1000))
                .termMonths(6)
                .applicantId("x")
                .fullName("x")
                .build();
        return ResponseEntity.ok(agent.previewAdaptation(dummy, productId));
    }

    @PostMapping("/adapt")
    public ResponseEntity<Map<String, Object>> adapt(@RequestBody AdaptRequest req) {
        LoanApplication app = LoanApplication.builder()
                .applicantId(req.applicantId() != null ? req.applicantId() : "preview")
                .fullName(req.fullName() != null ? req.fullName() : "Preview Applicant")
                .requestedAmount(req.requestedAmount())
                .termMonths(req.termMonths())
                .purpose(req.purpose())
                .build();
        return ResponseEntity.ok(agent.previewAdaptation(app, req.productId()));
    }

    @PostMapping("/brief")
    public ResponseEntity<Map<String, Object>> brief(@RequestBody BriefRequest req) {
        return ResponseEntity.ok(agent.brief(req.question(), req.externalId(), req.clientId()));
    }

    public record AdaptRequest(
            BigDecimal requestedAmount,
            Integer termMonths,
            Integer productId,
            String applicantId,
            String fullName,
            String purpose
    ) {}

    public record BriefRequest(
            String question,
            String externalId,
            Long clientId
    ) {}
}
