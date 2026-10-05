/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan;

import org.community.mifos.agentic.loan.config.OpenFnProperties;
import org.community.mifos.agentic.loan.openfn.OpenFnClient;
import org.community.mifos.agentic.loan.openfn.OpenFnClient.OpenFnTriggerResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenFnClientTest {

    private static final String WEBHOOK = "http://localhost:4000/i/loan-submit";

    @Test
    void triggerReadsRunIdFromLightningResponseHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenFnClient client = new OpenFnClient(new OpenFnProperties(), builder);

        // Lightning's webhook response: only work_order_id in the body, run id as a header
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-meta-run-id", "run-123");
        headers.add("x-meta-work-order-id", "wo-456");
        server.expect(requestTo(WEBHOOK))
                .andRespond(withSuccess("{\"work_order_id\":\"wo-456\"}", MediaType.APPLICATION_JSON)
                        .headers(headers));

        OpenFnTriggerResult result = client.triggerWebhook(WEBHOOK, Map.of("workflowId", "loan-1"));

        assertEquals("run-123", result.runId());
        assertEquals("wo-456", result.workOrderId());
        server.verify();
    }

    @Test
    void triggerFallsBackToBodyIdsWhenHeadersAbsent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenFnClient client = new OpenFnClient(new OpenFnProperties(), builder);

        server.expect(requestTo(WEBHOOK))
                .andRespond(withSuccess("{\"run_id\":\"run-789\",\"work_order_id\":\"wo-1\"}",
                        MediaType.APPLICATION_JSON));

        OpenFnTriggerResult result = client.triggerWebhook(WEBHOOK, Map.of("workflowId", "loan-2"));

        assertEquals("run-789", result.runId());
        assertEquals("wo-1", result.workOrderId());
    }
}
