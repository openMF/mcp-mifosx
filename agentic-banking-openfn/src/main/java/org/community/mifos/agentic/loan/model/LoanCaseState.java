/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>In-memory durable-ish case state held by the Agentic Gateway.
 * OpenFn owns step execution; the gateway owns HITL lifecycle and query surface
 * (status / summary / final) that Temporal previously provided via signals & queries.
 */
package org.community.mifos.agentic.loan.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LoanCaseState {

    public enum Status {
        STARTED,
        OPENFN_TRIGGERED,
        GATHERING_DATA,
        ASSESSING,
        LLM_DECISION,
        PENDING_HUMAN_REVIEW,
        WRITING_TO_FINERACT,
        COMPLETED,
        FAILED,
        FALLBACK_LOCAL
    }

    private final String workflowId;
    private final LoanApplication application;
    private volatile Status status = Status.STARTED;
    private volatile LoanDecision summary;
    private volatile LoanDecision finalResult;
    private final List<String> documentPaths = new ArrayList<>();
    private final Map<String, Object> intermediate = new ConcurrentHashMap<>();
    private String openfnRunId;
    private String openfnWorkOrderId;
    private String humanAction;
    private String humanComments;
    private String errorMessage;
    private final Instant createdAt = Instant.now();
    private volatile Instant updatedAt = Instant.now();

    public LoanCaseState(String workflowId, LoanApplication application) {
        this.workflowId = workflowId;
        this.application = application;
    }

    public String getWorkflowId() { return workflowId; }
    public LoanApplication getApplication() { return application; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public LoanDecision getSummary() { return summary; }
    public void setSummary(LoanDecision summary) {
        this.summary = summary;
        this.updatedAt = Instant.now();
    }

    public LoanDecision getFinalResult() {
        return finalResult != null ? finalResult : summary;
    }

    public void setFinalResult(LoanDecision finalResult) {
        this.finalResult = finalResult;
        this.updatedAt = Instant.now();
    }

    public List<String> getDocumentPaths() { return documentPaths; }

    public Map<String, Object> getIntermediate() { return intermediate; }

    public String getOpenfnRunId() { return openfnRunId; }
    public void setOpenfnRunId(String openfnRunId) { this.openfnRunId = openfnRunId; }

    public String getOpenfnWorkOrderId() { return openfnWorkOrderId; }
    public void setOpenfnWorkOrderId(String openfnWorkOrderId) { this.openfnWorkOrderId = openfnWorkOrderId; }

    public String getHumanAction() { return humanAction; }
    public void setHumanAction(String humanAction) { this.humanAction = humanAction; }

    public String getHumanComments() { return humanComments; }
    public void setHumanComments(String humanComments) { this.humanComments = humanComments; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
