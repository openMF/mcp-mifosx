/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>Thin HTTP client for OpenFn Lightning:
 * <ul>
 *   <li>Trigger workflows via webhook URLs (primary path)</li>
 *   <li>Optionally poll run / work-order status via Lightning REST API</li>
 * </ul>
 *
 * OpenFn webhook response (async mode):
 * <pre>
 * {
 *   "attempt_id": "...",
 *   "run_id": "...",
 *   "work_order_id": "..."
 * }
 * </pre>
 */
package org.community.mifos.agentic.loan.openfn;

import org.community.mifos.agentic.loan.config.OpenFnProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class OpenFnClient {

    private static final Logger log = LoggerFactory.getLogger(OpenFnClient.class);

    private final OpenFnProperties props;
    private final RestClient restClient;

    public OpenFnClient(OpenFnProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    /**
     * Fire a webhook trigger. Returns run metadata when Lightning responds with ids.
     */
    public OpenFnTriggerResult triggerWebhook(String webhookUrl, Map<String, Object> payload) {
        log.info("Triggering OpenFn webhook {} with workflowId={}",
                webhookUrl, payload.get("workflowId"));
        try {
            ResponseEntity<Map<String, Object>> response = restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {});

            // Lightning returns only work_order_id in the body; ids are also sent as
            // x-meta-run-id / x-meta-work-order-id response headers.
            HttpHeaders headers = response.getHeaders();
            Map<String, Object> body = response.getBody() != null ? response.getBody() : Map.of();
            return OpenFnTriggerResult.accepted(
                    firstNonNull(headers.getFirst("x-meta-run-id"), str(body.get("run_id"))),
                    firstNonNull(headers.getFirst("x-meta-work-order-id"), str(body.get("work_order_id"))),
                    str(body.get("attempt_id")));
        } catch (RestClientException ex) {
            log.error("OpenFn webhook trigger failed: {}", ex.getMessage());
            throw new OpenFnException("Failed to trigger OpenFn workflow: " + ex.getMessage(), ex);
        }
    }

    public OpenFnTriggerResult triggerLoanSubmit(Map<String, Object> payload) {
        return triggerWebhook(props.getWebhooks().getLoanSubmit(), payload);
    }

    public OpenFnTriggerResult triggerLoanReview(Map<String, Object> payload) {
        return triggerWebhook(props.getWebhooks().getLoanReview(), payload);
    }

    /**
     * Best-effort status lookup against Lightning API (requires api-token + project).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRun(String runId) {
        if (props.getApiToken() == null || props.getApiToken().isBlank()) {
            return Map.of("status", "unknown", "reason", "OPENFN_API_TOKEN not configured");
        }
        try {
            return restClient.get()
                    .uri(props.getBaseUrl() + "/api/runs/" + runId)
                    .header("Authorization", "Bearer " + props.getApiToken())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException ex) {
            log.warn("Could not fetch OpenFn run {}: {}", runId, ex.getMessage());
            return Map.of("status", "unknown", "error", ex.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    public record OpenFnTriggerResult(String runId, String workOrderId, String attemptId) {
        public static OpenFnTriggerResult accepted(String runId, String workOrderId, String attemptId) {
            return new OpenFnTriggerResult(runId, workOrderId, attemptId);
        }
    }

    public static class OpenFnException extends RuntimeException {
        public OpenFnException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
