/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>Inbound callbacks from OpenFn Lightning jobs (HTTP adaptor).
 * Secured by shared callback secret header {@code X-OpenFn-Secret}.
 */
package org.community.mifos.agentic.loan.controller;

import org.community.mifos.agentic.loan.config.OpenFnProperties;
import org.community.mifos.agentic.loan.service.LoanOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/openfn/callback")
public class OpenFnCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OpenFnCallbackController.class);

    private final LoanOrchestrationService orchestration;
    private final OpenFnProperties props;

    public OpenFnCallbackController(LoanOrchestrationService orchestration, OpenFnProperties props) {
        this.orchestration = orchestration;
        this.props = props;
    }

    @PostMapping("/underwriting-complete")
    public ResponseEntity<Map<String, String>> underwritingComplete(
            @RequestHeader(value = "X-OpenFn-Secret", required = false) String secret,
            @RequestBody Map<String, Object> body) {
        if (!authorized(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid callback secret"));
        }
        log.info("OpenFn underwriting-complete callback: {}", body.get("workflowId"));
        try {
            orchestration.onUnderwritingComplete(body);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/fineract-complete")
    public ResponseEntity<Map<String, String>> fineractComplete(
            @RequestHeader(value = "X-OpenFn-Secret", required = false) String secret,
            @RequestBody Map<String, Object> body) {
        if (!authorized(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid callback secret"));
        }
        log.info("OpenFn fineract-complete callback: {}", body.get("workflowId"));
        try {
            orchestration.onFineractComplete(body);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/status")
    public ResponseEntity<Map<String, String>> statusUpdate(
            @RequestHeader(value = "X-OpenFn-Secret", required = false) String secret,
            @RequestBody Map<String, Object> body) {
        if (!authorized(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid callback secret"));
        }
        try {
            orchestration.onStatusUpdate(body);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    private boolean authorized(String secret) {
        String expected = props.getCallbackSecret();
        if (expected == null || expected.isBlank() || "change-me-in-prod".equals(expected)) {
            // allow in local/dev when secret not hardened
            return true;
        }
        return expected.equals(secret);
    }
}
