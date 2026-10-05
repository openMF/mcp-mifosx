/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan;

import org.community.mifos.agentic.loan.config.LoanProperties;
import org.community.mifos.agentic.loan.config.OpenFnProperties;
import org.community.mifos.agentic.loan.dto.SubmitLoanRequest;
import org.community.mifos.agentic.loan.openfn.OpenFnClient;
import org.community.mifos.agentic.loan.service.LoanCaseStore;
import org.community.mifos.agentic.loan.service.LoanOrchestrationService;
import org.community.mifos.agentic.loan.service.LocalUnderwritingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SubmitPayloadTest {

    @Test
    void submitSendsOllamaSettingsToOpenFn() {
        OpenFnProperties openFnProps = new OpenFnProperties();
        openFnProps.setOllamaUrl("http://ollama.test:11434");
        openFnProps.setOllamaModel("llama3.2:3b");
        LoanProperties loanProps = new LoanProperties();

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(openFnProps.getWebhooks().getLoanSubmit()))
                .andExpect(jsonPath("$.ollamaUrl").value("http://ollama.test:11434"))
                .andExpect(jsonPath("$.ollamaModel").value("llama3.2:3b"))
                .andRespond(withSuccess("{\"work_order_id\":\"wo-1\"}", MediaType.APPLICATION_JSON));

        LoanOrchestrationService svc = new LoanOrchestrationService(
                new LoanCaseStore(loanProps),
                new OpenFnClient(openFnProps, builder),
                new LocalUnderwritingService(),
                loanProps,
                openFnProps);

        SubmitLoanRequest req = new SubmitLoanRequest();
        req.setApplicantId("APP-1");
        req.setFullName("Test User");
        req.setRequestedAmount(new BigDecimal("20000"));
        svc.submit(req);

        server.verify();
    }
}
