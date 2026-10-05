/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>Agentic Gateway orchestration layer.
 *
 * <p>Replaces Temporal SupervisorWorkflow:
 * <ul>
 *   <li>Case state + HITL is owned here (status / summary / final queries)</li>
 *   <li>Durable multi-step execution is delegated to OpenFn Lightning workflows</li>
 *   <li>OpenFn jobs callback into {@code /api/openfn/callback/*} to update case state</li>
 *   <li>Optional local underwriting fallback when Lightning is down</li>
 * </ul>
 */
package org.community.mifos.agentic.loan.service;

import org.community.mifos.agentic.loan.config.LoanProperties;
import org.community.mifos.agentic.loan.config.OpenFnProperties;
import org.community.mifos.agentic.loan.dto.HumanReviewRequest;
import org.community.mifos.agentic.loan.dto.SubmitLoanRequest;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanCaseState;
import org.community.mifos.agentic.loan.model.LoanDecision;
import org.community.mifos.agentic.loan.openfn.OpenFnClient;
import org.community.mifos.agentic.loan.openfn.OpenFnClient.OpenFnException;
import org.community.mifos.agentic.loan.openfn.OpenFnClient.OpenFnTriggerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LoanOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(LoanOrchestrationService.class);

    private final LoanCaseStore store;
    private final OpenFnClient openFnClient;
    private final LocalUnderwritingService localUnderwriting;
    private final LoanProperties loanProps;
    private final OpenFnProperties openFnProps;

    public LoanOrchestrationService(
            LoanCaseStore store,
            OpenFnClient openFnClient,
            LocalUnderwritingService localUnderwriting,
            LoanProperties loanProps,
            OpenFnProperties openFnProps) {
        this.store = store;
        this.openFnClient = openFnClient;
        this.localUnderwriting = localUnderwriting;
        this.loanProps = loanProps;
        this.openFnProps = openFnProps;
    }

    public Map<String, Object> submit(SubmitLoanRequest req) {
        String workflowId = "loan-" + req.getApplicantId() + "-"
                + UUID.randomUUID().toString().substring(0, 8);

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

        LoanCaseState state = new LoanCaseState(workflowId, app);
        store.put(state);

        Map<String, Object> payload = buildSubmitPayload(app);

        try {
            OpenFnTriggerResult result = openFnClient.triggerLoanSubmit(payload);
            state.setOpenfnRunId(result.runId());
            state.setOpenfnWorkOrderId(result.workOrderId());
            state.setStatus(LoanCaseState.Status.OPENFN_TRIGGERED);
            log.info("OpenFn loan-submit triggered workflowId={} runId={} workOrderId={}",
                    workflowId, result.runId(), result.workOrderId());

            return Map.of(
                    "workflowId", workflowId,
                    "status", state.getStatus().name(),
                    "openfnRunId", nullToEmpty(result.runId()),
                    "openfnWorkOrderId", nullToEmpty(result.workOrderId()),
                    "message", "Loan workflow started on OpenFn. Poll /api/loans/{id}/status then POST review."
            );
        } catch (OpenFnException ex) {
            if (!loanProps.isLocalFallbackEnabled()) {
                state.setStatus(LoanCaseState.Status.FAILED);
                state.setErrorMessage(ex.getMessage());
                throw ex;
            }
            log.warn("OpenFn unavailable – running local underwriting fallback for {}", workflowId);
            LoanDecision decision = localUnderwriting.underwrite(app);
            decision.setWorkflowId(workflowId);
            state.setSummary(decision);
            state.setStatus(LoanCaseState.Status.PENDING_HUMAN_REVIEW);
            // mark that we used local path
            state.getIntermediate().put("fallback", true);

            return Map.of(
                    "workflowId", workflowId,
                    "status", state.getStatus().name(),
                    "message", "OpenFn unreachable – local underwriting completed. Awaiting human review.",
                    "fallback", true
            );
        }
    }

    public Map<String, Object> status(String workflowId) {
        LoanCaseState state = require(workflowId);
        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId);
        body.put("status", state.getStatus().name());
        body.put("openfnRunId", state.getOpenfnRunId());
        body.put("openfnWorkOrderId", state.getOpenfnWorkOrderId());
        body.put("updatedAt", state.getUpdatedAt().toString());
        if (state.getErrorMessage() != null) {
            body.put("error", state.getErrorMessage());
        }
        return body;
    }

    public LoanDecision summary(String workflowId) {
        LoanCaseState state = require(workflowId);
        LoanDecision d = state.getSummary();
        if (d == null) {
            return null;
        }
        return d;
    }

    public LoanDecision finalResult(String workflowId) {
        LoanCaseState state = require(workflowId);
        return state.getFinalResult();
    }

    public Map<String, Object> humanReview(String workflowId, HumanReviewRequest req) {
        LoanCaseState state = require(workflowId);
        if (state.getStatus() != LoanCaseState.Status.PENDING_HUMAN_REVIEW
                && state.getStatus() != LoanCaseState.Status.FALLBACK_LOCAL) {
            // allow review also right after local fallback which already sets PENDING_HUMAN_REVIEW
            if (state.getSummary() == null) {
                throw new IllegalStateException(
                        "Case is not ready for human review. Current status=" + state.getStatus());
            }
        }

        String action = req.getAction().toUpperCase();
        state.setHumanAction(action);
        state.setHumanComments(req.getComments());

        LoanDecision decision = state.getSummary();
        if (decision == null) {
            decision = new LoanDecision();
            decision.setWorkflowId(workflowId);
            state.setSummary(decision);
        }
        decision.setHumanDecision(action);

        if ("APPROVE".equals(action)) {
            decision.setFinalStatus("APPROVED");
            // Hand off Fineract write-back to OpenFn (or complete locally if fallback)
            boolean usedFallback = Boolean.TRUE.equals(state.getIntermediate().get("fallback"));
            if (usedFallback) {
                decision.setFineractLoan(Map.of(
                        "note", "Local fallback – Fineract write-back skipped. Configure OpenFn for production write-back."
                ));
                state.setFinalResult(decision);
                state.setStatus(LoanCaseState.Status.COMPLETED);
            } else {
                state.setStatus(LoanCaseState.Status.WRITING_TO_FINERACT);
                Map<String, Object> payload = buildReviewPayload(state, action, req.getComments());
                try {
                    OpenFnTriggerResult result = openFnClient.triggerLoanReview(payload);
                    state.setOpenfnRunId(result.runId());
                    log.info("OpenFn loan-review triggered workflowId={} runId={}", workflowId, result.runId());
                } catch (OpenFnException ex) {
                    log.error("OpenFn review trigger failed: {}", ex.getMessage());
                    decision.setFineractLoan(Map.of("error", ex.getMessage()));
                    state.setFinalResult(decision);
                    state.setStatus(LoanCaseState.Status.COMPLETED);
                }
            }
        } else {
            decision.setFinalStatus("REJECTED");
            state.setFinalResult(decision);
            state.setStatus(LoanCaseState.Status.COMPLETED);
        }

        return Map.of(
                "workflowId", workflowId,
                "action", action,
                "status", state.getStatus().name(),
                "message", "Human review recorded"
        );
    }

    public void documentsUploaded(String workflowId, List<String> paths) {
        LoanCaseState state = require(workflowId);
        if (paths != null) {
            state.getDocumentPaths().addAll(paths);
        }
    }

    // ------------------------------------------------------------------
    // Callbacks from OpenFn jobs (HTTP adaptor posts back here)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public void onUnderwritingComplete(Map<String, Object> body) {
        String workflowId = str(body.get("workflowId"));
        LoanCaseState state = require(workflowId);

        LoanDecision decision = new LoanDecision();
        decision.setWorkflowId(workflowId);
        decision.setRecommendation(str(body.get("recommendation")));
        decision.setConfidence(body.get("confidence") instanceof Number n ? n.doubleValue() : 0.5);
        decision.setRationale(str(body.get("rationale")));
        decision.setOpenfnRunId(str(body.get("runId")));
        decision.setFinalStatus("PENDING_HUMAN_REVIEW");

        if (body.get("assessments") instanceof List<?> list) {
            // leave raw; OpenFn jobs send compatible maps – gateway stores as context
            decision.setContext(Map.of("assessments", list));
        }
        if (body.get("context") instanceof Map<?, ?> ctx) {
            decision.setContext((Map<String, Object>) ctx);
        }

        state.setSummary(decision);
        state.setStatus(LoanCaseState.Status.PENDING_HUMAN_REVIEW);
        log.info("Underwriting complete for {} → PENDING_HUMAN_REVIEW (rec={})",
                workflowId, decision.getRecommendation());
    }

    @SuppressWarnings("unchecked")
    public void onFineractComplete(Map<String, Object> body) {
        String workflowId = str(body.get("workflowId"));
        LoanCaseState state = require(workflowId);

        LoanDecision decision = state.getSummary();
        if (decision == null) {
            decision = new LoanDecision();
            decision.setWorkflowId(workflowId);
        }
        decision.setHumanDecision(state.getHumanAction());

        if (body.get("fineractLoan") instanceof Map<?, ?> fl) {
            decision.setFineractLoan((Map<String, Object>) fl);
        } else if (body.get("error") != null) {
            decision.setFineractLoan(Map.of("error", str(body.get("error"))));
        }

        Map<String, Object> fineractLoan = decision.getFineractLoan();
        Object error = fineractLoan != null ? fineractLoan.get("error") : null;
        if (error != null || (fineractLoan != null && "FINERACT_ERROR".equals(fineractLoan.get("status")))) {
            // Write-back failed: keep the case retryable (POST review again) instead of COMPLETED
            decision.setFinalStatus("FINERACT_ERROR");
            state.setErrorMessage("Fineract write-back failed: " + error);
            state.setFinalResult(decision);
            state.setStatus(LoanCaseState.Status.FAILED);
            log.warn("Fineract write-back failed for {}: {}", workflowId, error);
            return;
        }

        decision.setFinalStatus("APPROVED");
        state.setErrorMessage(null);
        state.setFinalResult(decision);
        state.setStatus(LoanCaseState.Status.COMPLETED);
        log.info("Fineract write-back complete for {}", workflowId);
    }

    public void onStatusUpdate(Map<String, Object> body) {
        String workflowId = str(body.get("workflowId"));
        LoanCaseState state = require(workflowId);
        String phase = str(body.get("phase"));
        if (phase != null) {
            try {
                state.setStatus(LoanCaseState.Status.valueOf(phase));
            } catch (IllegalArgumentException ignored) {
                state.getIntermediate().put("lastPhase", phase);
            }
        }
        if (body.get("data") instanceof Map<?, ?> data) {
            state.getIntermediate().putAll((Map<String, Object>) data);
        }
    }

    // ------------------------------------------------------------------

    private Map<String, Object> buildSubmitPayload(LoanApplication app) {
        Map<String, Object> p = new HashMap<>();
        p.put("workflowId", app.getWorkflowId());
        p.put("applicantId", app.getApplicantId());
        p.put("fullName", app.getFullName());
        p.put("email", app.getEmail());
        p.put("phone", app.getPhone());
        p.put("requestedAmount", app.getRequestedAmount());
        p.put("termMonths", app.getTermMonths());
        p.put("purpose", app.getPurpose());
        p.put("applicationDate", app.getApplicationDate() != null ? app.getApplicationDate().toString() : null);
        p.put("documentPaths", app.getDocumentPaths());
        p.put("metadata", app.getMetadata());
        // Callback target for OpenFn jobs
        p.put("gatewayBaseUrl", "http://host.docker.internal:8080"); // override via env in real deploy
        p.put("callbackSecret", openFnProps.getCallbackSecret());
        return p;
    }

    private Map<String, Object> buildReviewPayload(LoanCaseState state, String action, String comments) {
        Map<String, Object> p = new HashMap<>();
        p.put("workflowId", state.getWorkflowId());
        p.put("action", action);
        p.put("comments", comments);
        p.put("application", state.getApplication());
        p.put("summary", state.getSummary());
        p.put("gatewayBaseUrl", "http://host.docker.internal:8080");
        p.put("callbackSecret", openFnProps.getCallbackSecret());
        return p;
    }

    private LoanCaseState require(String workflowId) {
        return store.get(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflowId: " + workflowId));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
