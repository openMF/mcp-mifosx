/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.community.mifos.agentic.loan.config.LoanProperties;
import org.community.mifos.agentic.loan.config.OpenFnProperties;
import org.community.mifos.agentic.loan.dto.HumanReviewRequest;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanCaseState;
import org.community.mifos.agentic.loan.model.LoanDecision;
import org.community.mifos.agentic.loan.openfn.OpenFnClient;
import org.community.mifos.agentic.loan.service.LoanCaseStore;
import org.community.mifos.agentic.loan.service.LoanOrchestrationService;
import org.community.mifos.agentic.loan.service.LocalUnderwritingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A failed OpenFn job stops the run without calling back. The gateway's deadline must then
 * mark the case FAILED instead of leaving it in flight forever, and retried or late callbacks
 * must never move a case backwards.
 */
class StalledCaseTest {

    private final LoanProperties loanProps = new LoanProperties();
    private final OpenFnProperties openFnProps = new OpenFnProperties();
    private final LoanCaseStore store = new LoanCaseStore(loanProps);
    // Stands in for Lightning's review webhook, for the tests that send a review.
    private final RestClient.Builder restBuilder = RestClient.builder();
    private final MockRestServiceServer lightning = MockRestServiceServer.bindTo(restBuilder).build();
    private final LoanOrchestrationService svc = new LoanOrchestrationService(
            store,
            new OpenFnClient(openFnProps, restBuilder),
            new LocalUnderwritingService(),
            loanProps,
            openFnProps);

    private LoanCaseState seedCase(String workflowId, LoanCaseState.Status status) {
        LoanApplication app = LoanApplication.builder()
                .workflowId(workflowId)
                .applicantId("APP-1")
                .fullName("Test User")
                .requestedAmount(new BigDecimal("20000"))
                .termMonths(12)
                .build();
        LoanCaseState state = new LoanCaseState(workflowId, app);
        state.setOpenfnRunId("run-" + workflowId);
        state.setStatus(status);
        store.put(state);
        return state;
    }

    private LoanCaseState seedReviewedCase(String workflowId, LoanCaseState.Status status) {
        LoanCaseState state = seedCase(workflowId, status);
        LoanDecision summary = new LoanDecision();
        summary.setWorkflowId(workflowId);
        state.setSummary(summary);
        state.setHumanAction("APPROVE");
        return state;
    }

    private static HumanReviewRequest review(String action) {
        HumanReviewRequest review = new HumanReviewRequest();
        review.setAction(action);
        return review;
    }

    private void expectReviewWebhook() {
        lightning.expect(requestTo(openFnProps.getWebhooks().getLoanReview()))
                .andRespond(withSuccess("{\"work_order_id\":\"wo-1\"}", MediaType.APPLICATION_JSON));
    }

    // ── the deadline ────────────────────────────────────────────────

    @Test
    void caseWaitingOnOpenFnPastTheDeadlineIsMarkedFailed() {
        seedCase("loan-st-1", LoanCaseState.Status.GATHERING_DATA);

        assertEquals(1, svc.failStalledCases(Duration.ZERO));

        Map<String, Object> status = svc.status("loan-st-1");
        assertEquals("FAILED", status.get("status"));
        String error = String.valueOf(status.get("error"));
        assertTrue(error.contains("GATHERING_DATA"), error);
        assertTrue(error.contains("run-loan-st-1"), error);
        assertTrue(error.contains("Submit the application again"), error);
    }

    @Test
    void casesNotWaitingOnOpenFnAreLeftAlone() {
        seedCase("loan-st-3", LoanCaseState.Status.PENDING_HUMAN_REVIEW);
        seedCase("loan-st-4", LoanCaseState.Status.COMPLETED);

        assertEquals(0, svc.failStalledCases(Duration.ZERO));
        assertEquals("PENDING_HUMAN_REVIEW", svc.status("loan-st-3").get("status"));
        assertEquals("COMPLETED", svc.status("loan-st-4").get("status"));
    }

    @Test
    void caseWithinTheDeadlineIsLeftAlone() {
        seedCase("loan-st-5", LoanCaseState.Status.OPENFN_TRIGGERED);

        assertEquals(0, svc.failStalledCases(Duration.ofMinutes(loanProps.getOpenfnTimeoutMinutes())));
        assertEquals("OPENFN_TRIGGERED", svc.status("loan-st-5").get("status"));
    }

    @Test
    void scanSkipsABusyCaseWithoutWaitingForIt() throws InterruptedException {
        LoanCaseState busy = seedCase("loan-st-12", LoanCaseState.Status.GATHERING_DATA);
        seedCase("loan-st-13", LoanCaseState.Status.GATHERING_DATA);
        // Another thread holds the busy case's lock, as during a slow OpenFn call.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            busy.getLock().lock();
            try {
                locked.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                busy.getLock().unlock();
            }
        });
        holder.start();
        locked.await();

        int failed = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> svc.failStalledCases(Duration.ZERO));
        assertEquals(1, failed);
        assertEquals("GATHERING_DATA", svc.status("loan-st-12").get("status"));
        assertEquals("FAILED", svc.status("loan-st-13").get("status"));

        release.countDown();
        holder.join();
        assertEquals(1, svc.failStalledCases(Duration.ZERO));
        assertEquals("FAILED", svc.status("loan-st-12").get("status"));
    }

    @Test
    void openFnTimeoutMustBePositive() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        LoanProperties props = new LoanProperties();
        assertTrue(validator.validate(props).isEmpty());

        props.setOpenfnTimeoutMinutes(0);
        assertFalse(validator.validate(props).isEmpty());
    }

    // ── late, repeated and progress callbacks ──────────────────────

    @Test
    void lateCallbackRecoversAFailedCase() {
        seedCase("loan-st-6", LoanCaseState.Status.LLM_DECISION);
        svc.failStalledCases(Duration.ZERO);

        svc.onUnderwritingComplete(Map.of(
                "workflowId", "loan-st-6",
                "recommendation", "APPROVE",
                "confidence", 0.9,
                "rationale", "late but fine"));

        Map<String, Object> status = svc.status("loan-st-6");
        assertEquals("PENDING_HUMAN_REVIEW", status.get("status"));
        assertNull(status.get("error"));
    }

    @Test
    void repeatedUnderwritingCallbackDoesNotUndoAReview() {
        seedCase("loan-st-7", LoanCaseState.Status.LLM_DECISION);
        Map<String, Object> callback = Map.of("workflowId", "loan-st-7", "recommendation", "APPROVE", "confidence", 0.9);
        svc.onUnderwritingComplete(callback);
        expectReviewWebhook();
        svc.humanReview("loan-st-7", review("APPROVE"));

        // A retry of the same callback (its first response was lost) arrives after the review.
        svc.onUnderwritingComplete(callback);

        assertEquals("WRITING_TO_FINERACT", svc.status("loan-st-7").get("status"));
        assertEquals("APPROVE", svc.summary("loan-st-7").getHumanDecision());
    }

    @Test
    void progressUpdatesCountAsActivityAndNeverMoveBackwards() throws InterruptedException {
        LoanCaseState state = seedCase("loan-st-8", LoanCaseState.Status.GATHERING_DATA);

        Instant before = state.getUpdatedAt();
        Thread.sleep(5);
        svc.onStatusUpdate(Map.of("workflowId", "loan-st-8", "phase", "READING_DOCUMENT"));
        assertTrue(state.getUpdatedAt().isAfter(before), "an unrecognised phase still counts as activity");
        assertEquals("GATHERING_DATA", svc.status("loan-st-8").get("status"));

        svc.onStatusUpdate(Map.of("workflowId", "loan-st-8", "phase", "LLM_DECISION"));
        assertEquals("LLM_DECISION", svc.status("loan-st-8").get("status"));

        svc.onStatusUpdate(Map.of("workflowId", "loan-st-8", "phase", "GATHERING_DATA"));
        assertEquals("LLM_DECISION", svc.status("loan-st-8").get("status"));
    }

    @Test
    void progressAfterTheCaseMovedOnIsIgnored() {
        seedReviewedCase("loan-st-9", LoanCaseState.Status.PENDING_HUMAN_REVIEW).setHumanAction(null);

        svc.onStatusUpdate(Map.of("workflowId", "loan-st-9", "phase", "GATHERING_DATA"));

        assertEquals("PENDING_HUMAN_REVIEW", svc.status("loan-st-9").get("status"));
    }

    @Test
    void repeatedFineractCallbackIsIgnoredOnceCompleted() {
        seedReviewedCase("loan-st-10", LoanCaseState.Status.WRITING_TO_FINERACT);
        svc.onFineractComplete(Map.of("workflowId", "loan-st-10",
                "fineractLoan", Map.of("status", "APPROVED_IN_FINERACT", "loanId", 2)));

        svc.onFineractComplete(Map.of("workflowId", "loan-st-10",
                "fineractLoan", Map.of("status", "FINERACT_ERROR", "error", "stale")));

        assertEquals("COMPLETED", svc.status("loan-st-10").get("status"));
    }

    // ── reviews after an approval went to Fineract ─────────────────

    @Test
    void reviewIsRefusedWhileTheWriteBackRuns() {
        seedReviewedCase("loan-st-11", LoanCaseState.Status.WRITING_TO_FINERACT);

        assertThrows(IllegalStateException.class, () -> svc.humanReview("loan-st-11", review("APPROVE")));
    }

    @Test
    void stalledWriteBackCanOnlyBeRetriedWithApprove() {
        seedReviewedCase("loan-st-2", LoanCaseState.Status.WRITING_TO_FINERACT);
        svc.failStalledCases(Duration.ZERO);

        Map<String, Object> status = svc.status("loan-st-2");
        assertEquals("FAILED", status.get("status"));
        assertTrue(String.valueOf(status.get("error")).contains("with APPROVE to retry"));

        // The loan may already exist in Fineract, so a different decision is refused...
        assertThrows(IllegalStateException.class, () -> svc.humanReview("loan-st-2", review("REJECT")));
        // ...and APPROVE retries the (retry-safe) write-back.
        expectReviewWebhook();
        assertEquals("WRITING_TO_FINERACT", svc.humanReview("loan-st-2", review("APPROVE")).get("status"));
        lightning.verify();
    }
}
