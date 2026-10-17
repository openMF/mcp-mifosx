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
import org.community.mifos.agentic.loan.document.DocumentImages;
import org.community.mifos.agentic.loan.dto.HumanReviewRequest;
import org.community.mifos.agentic.loan.dto.SubmitLoanRequest;
import org.community.mifos.agentic.loan.model.AssessmentResult;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanCaseState;
import org.community.mifos.agentic.loan.model.LoanDecision;
import org.community.mifos.agentic.loan.openfn.OpenFnClient;
import org.community.mifos.agentic.loan.openfn.OpenFnClient.OpenFnException;
import org.community.mifos.agentic.loan.openfn.OpenFnClient.OpenFnTriggerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LoanOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(LoanOrchestrationService.class);

    // Statuses of a case whose underwriting run has not reported its result yet, in run order.
    private static final Set<LoanCaseState.Status> UNDERWRITING = EnumSet.of(
            LoanCaseState.Status.STARTED,
            LoanCaseState.Status.OPENFN_TRIGGERED,
            LoanCaseState.Status.GATHERING_DATA,
            LoanCaseState.Status.ASSESSING,
            LoanCaseState.Status.LLM_DECISION);

    // Statuses in which the case waits for an OpenFn run to call back.
    private static final Set<LoanCaseState.Status> WAITING_ON_OPENFN = EnumSet.of(
            LoanCaseState.Status.STARTED,
            LoanCaseState.Status.OPENFN_TRIGGERED,
            LoanCaseState.Status.GATHERING_DATA,
            LoanCaseState.Status.ASSESSING,
            LoanCaseState.Status.LLM_DECISION,
            LoanCaseState.Status.WRITING_TO_FINERACT);

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

        // The run's first callback can arrive before the trigger returns; it waits for this
        // lock, so OPENFN_TRIGGERED below never overwrites progress it reports.
        state.getLock().lock();
        try {
            return trigger(state, app, payload);
        } finally {
            state.getLock().unlock();
        }
    }

    private Map<String, Object> trigger(LoanCaseState state, LoanApplication app, Map<String, Object> payload) {
        String workflowId = state.getWorkflowId();
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
        // Callbacks and the stall check change the same case; one at a time.
        state.getLock().lock();
        try {
            if (state.getStatus() == LoanCaseState.Status.WRITING_TO_FINERACT) {
                throw new IllegalStateException(
                        "The approval is still being written to Fineract; wait for the result.");
            }
            // Once an approval has gone to Fineract, the loan may exist there whatever the
            // gateway saw. Only APPROVE may follow: it retries the write-back, which reuses the
            // client and loan if they were created, so the gateway and Fineract cannot disagree.
            if ("APPROVE".equals(state.getHumanAction()) && !"APPROVE".equalsIgnoreCase(req.getAction())) {
                throw new IllegalStateException("An approval was already sent to Fineract; "
                        + "only APPROVE can be sent again, to retry the write-back.");
            }
            return review(state, req);
        } finally {
            state.getLock().unlock();
        }
    }

    private Map<String, Object> review(LoanCaseState state, HumanReviewRequest req) {
        String workflowId = state.getWorkflowId();
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

    public void onUnderwritingComplete(Map<String, Object> body) {
        String workflowId = str(body.get("workflowId"));
        LoanCaseState state = require(workflowId);
        state.getLock().lock();
        try {
            if (!awaitingUnderwriting(state)) {
                // A retried callback whose first attempt was already processed, or a late one:
                // the case has moved on (a human may have reviewed it), so leave it alone.
                log.info("Ignoring underwriting callback for {} in status {}", workflowId, state.getStatus());
                return;
            }
            applyUnderwriting(state, body);
        } finally {
            state.getLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void applyUnderwriting(LoanCaseState state, Map<String, Object> body) {
        String workflowId = state.getWorkflowId();
        LoanDecision decision = new LoanDecision();
        decision.setWorkflowId(workflowId);
        decision.setRecommendation(str(body.get("recommendation")));
        decision.setConfidence(body.get("confidence") instanceof Number n ? n.doubleValue() : 0.5);
        decision.setRationale(str(body.get("rationale")));
        decision.setOpenfnRunId(str(body.get("runId")));
        decision.setFinalStatus("PENDING_HUMAN_REVIEW");

        if (body.get("assessments") instanceof List<?> list) {
            decision.setAssessments(toAssessments(list));
        }
        if (body.get("context") instanceof Map<?, ?> ctx) {
            decision.setContext((Map<String, Object>) ctx);
        }

        state.setSummary(decision);
        // A slow run may call back after the case was marked FAILED.
        state.setErrorMessage(null);
        state.setStatus(LoanCaseState.Status.PENDING_HUMAN_REVIEW);
        log.info("Underwriting complete for {} → PENDING_HUMAN_REVIEW (rec={})",
                workflowId, decision.getRecommendation());
    }

    public void onFineractComplete(Map<String, Object> body) {
        String workflowId = str(body.get("workflowId"));
        LoanCaseState state = require(workflowId);
        state.getLock().lock();
        try {
            // Expected while the write-back runs, or late after it was marked FAILED. Anything
            // else is a duplicate of a callback already applied.
            boolean writeBackPending = state.getStatus() == LoanCaseState.Status.WRITING_TO_FINERACT
                    || (state.getStatus() == LoanCaseState.Status.FAILED && "APPROVE".equals(state.getHumanAction()));
            if (!writeBackPending) {
                log.info("Ignoring Fineract callback for {} in status {}", workflowId, state.getStatus());
                return;
            }
            applyFineractResult(state, body);
        } finally {
            state.getLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void applyFineractResult(LoanCaseState state, Map<String, Object> body) {
        String workflowId = state.getWorkflowId();
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

    @SuppressWarnings("unchecked")
    public void onStatusUpdate(Map<String, Object> body) {
        String workflowId = str(body.get("workflowId"));
        LoanCaseState state = require(workflowId);
        state.getLock().lock();
        try {
            if (!awaitingUnderwriting(state)) {
                log.info("Ignoring progress update for {} in status {}", workflowId, state.getStatus());
                return;
            }
            String phase = str(body.get("phase"));
            LoanCaseState.Status next = parseStatus(phase);
            boolean forward = next != null && UNDERWRITING.contains(next)
                    && (state.getStatus() == LoanCaseState.Status.FAILED || next.compareTo(state.getStatus()) >= 0);
            if (forward) {
                state.setStatus(next);
                state.setErrorMessage(null);
            } else {
                if (phase != null && next == null) {
                    state.getIntermediate().put("lastPhase", phase);
                }
                // Any accepted update shows the run is alive, even when the status stays.
                state.touch();
            }
            if (body.get("data") instanceof Map<?, ?> data) {
                state.getIntermediate().putAll((Map<String, Object>) data);
            }
        } finally {
            state.getLock().unlock();
        }
    }

    /**
     * True while the case still expects its underwriting result: it is in an underwriting
     * status, or it was marked FAILED by the stall check before any result arrived.
     */
    private static boolean awaitingUnderwriting(LoanCaseState state) {
        return UNDERWRITING.contains(state.getStatus())
                || (state.getStatus() == LoanCaseState.Status.FAILED && state.getSummary() == null);
    }

    private static LoanCaseState.Status parseStatus(String phase) {
        if (phase == null) {
            return null;
        }
        try {
            return LoanCaseState.Status.valueOf(phase);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Deadline for OpenFn runs
    // ------------------------------------------------------------------

    /**
     * Runs {@link #failStalledCases(Duration)} every 30 seconds with the configured
     * {@code loan.openfn-timeout-minutes}.
     */
    @Scheduled(fixedDelayString = "${loan.stall-check-interval-ms:30000}")
    public void failStalledCases() {
        failStalledCases(Duration.ofMinutes(loanProps.getOpenfnTimeoutMinutes()));
    }

    /**
     * Marks FAILED every case that has waited on OpenFn for longer than {@code timeout}
     * without any progress. A failed job stops the run without calling back, so without
     * a deadline such a case would stay in flight forever. A late callback still moves
     * the case on.
     *
     * @return how many cases were marked FAILED
     */
    public int failStalledCases(Duration timeout) {
        Instant cutoff = Instant.now().minus(timeout);
        int failed = 0;
        for (LoanCaseState state : store.all()) {
            // Check and mark under the case's lock, so a callback cannot slip in between. A case
            // whose lock is held is being submitted, reviewed or updated right now, so it is
            // active rather than stalled; skip it instead of waiting, because the holder may be
            // in an OpenFn call (up to the client's read timeout) and every later case would wait
            // too. The next scan looks at it again.
            if (!state.getLock().tryLock()) {
                continue;
            }
            try {
                LoanCaseState.Status status = state.getStatus();
                if (!WAITING_ON_OPENFN.contains(status) || state.getUpdatedAt().isAfter(cutoff)) {
                    continue;
                }
                String retry = status == LoanCaseState.Status.WRITING_TO_FINERACT
                        ? " POST the review again with APPROVE to retry; the write-back reuses the client"
                                + " and loan if they were already created."
                        : " Submit the application again.";
                state.setErrorMessage(String.format(
                        "No result from OpenFn after %d minutes in %s (run %s); check the run in Lightning.%s",
                        timeout.toMinutes(), status, state.getOpenfnRunId(), retry));
                state.setStatus(LoanCaseState.Status.FAILED);
                log.warn("Case {} made no progress for {} minutes in {} (run {}); marked FAILED",
                        state.getWorkflowId(), timeout.toMinutes(), status, state.getOpenfnRunId());
                failed++;
            } finally {
                state.getLock().unlock();
            }
        }
        return failed;
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
        p.put("gatewayBaseUrl", openFnProps.getGatewayBaseUrl());
        p.put("callbackSecret", openFnProps.getCallbackSecret());
        p.put("ollamaUrl", openFnProps.getOllamaUrl());
        p.put("ollamaModel", openFnProps.getOllamaModel());
        p.put("visionModel", openFnProps.getVisionModel());
        p.put("curpMaxIssueAgeDays", openFnProps.getCurpMaxIssueAgeDays());
        p.put("documents", DocumentImages.prepare(app.getDocumentPaths(), openFnProps.getDocumentRenderDpi()));
        return p;
    }

    private Map<String, Object> buildReviewPayload(LoanCaseState state, String action, String comments) {
        Map<String, Object> p = new HashMap<>();
        p.put("workflowId", state.getWorkflowId());
        p.put("action", action);
        p.put("comments", comments);
        p.put("application", state.getApplication());
        p.put("summary", state.getSummary());
        p.put("gatewayBaseUrl", openFnProps.getGatewayBaseUrl());
        p.put("callbackSecret", openFnProps.getCallbackSecret());
        return p;
    }

    private static List<AssessmentResult> toAssessments(List<?> raw) {
        List<AssessmentResult> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Map<?, ?> m) {
                out.add(new AssessmentResult(
                        str(m.get("type")),
                        str(m.get("verdict")),
                        m.get("score") instanceof Number n ? n.doubleValue() : 0.0,
                        str(m.get("rationale"))));
            }
        }
        return out;
    }

    private LoanCaseState require(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId is required");
        }
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
