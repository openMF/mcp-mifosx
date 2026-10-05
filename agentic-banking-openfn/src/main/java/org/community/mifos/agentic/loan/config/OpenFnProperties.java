/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openfn")
public class OpenFnProperties {

    private String baseUrl = "http://localhost:4000";
    private String apiToken = "";
    private String projectId = "";
    private String callbackSecret = "change-me-in-prod";
    private String gatewayBaseUrl = "http://host.docker.internal:8080";
    private Webhooks webhooks = new Webhooks();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getCallbackSecret() { return callbackSecret; }
    public void setCallbackSecret(String callbackSecret) { this.callbackSecret = callbackSecret; }

    public String getGatewayBaseUrl() { return gatewayBaseUrl; }
    public void setGatewayBaseUrl(String gatewayBaseUrl) { this.gatewayBaseUrl = gatewayBaseUrl; }

    public Webhooks getWebhooks() { return webhooks; }
    public void setWebhooks(Webhooks webhooks) { this.webhooks = webhooks; }

    public static class Webhooks {
        private String loanSubmit = "http://localhost:4000/i/loan-submit";
        private String loanReview = "http://localhost:4000/i/loan-review";

        public String getLoanSubmit() { return loanSubmit; }
        public void setLoanSubmit(String loanSubmit) { this.loanSubmit = loanSubmit; }

        public String getLoanReview() { return loanReview; }
        public void setLoanReview(String loanReview) { this.loanReview = loanReview; }
    }
}
