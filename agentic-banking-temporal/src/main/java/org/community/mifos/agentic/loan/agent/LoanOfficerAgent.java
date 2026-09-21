/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.agentic.loan.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.community.mifos.agentic.loan.fineract.FineractClient;
import org.community.mifos.agentic.loan.model.AdaptedLoanRequest;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanProductTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loan Officer agent – presents existing Fineract loans, explains product
 * constraints, and recommends application adjustments using local Ollama.
 */
@Service
public class LoanOfficerAgent {

    private static final Logger log = LoggerFactory.getLogger(LoanOfficerAgent.class);

    private final FineractClient fineractClient;
    private final WebClient ollama;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public LoanOfficerAgent(
            FineractClient fineractClient,
            WebClient.Builder builder,
            @Value("${loan.ollama.base-url:http://localhost:11434}") String ollamaUrl,
            @Value("${loan.ollama.model:llama3.2:latest}") String model) {
        this.fineractClient = fineractClient;
        this.ollama = builder.baseUrl(ollamaUrl).build();
        this.model = model;
    }

    public Map<String, Object> portfolio(String externalId, Long clientId, int limit) {
        List<Map<String, Object>> loans = fineractClient.searchLoans(externalId, clientId, limit);
        List<Map<String, Object>> products = fineractClient.listLoanProducts();
        LoanProductTemplate active = fineractClient.getLoanProductTemplate(fineractClient.getDefaultProductId());

        Map<String, Object> out = new HashMap<>();
        out.put("loans", loans);
        out.put("loanCount", loans.size());
        out.put("products", products.stream().map(p -> Map.of(
                "id", p.getOrDefault("id", ""),
                "name", p.getOrDefault("name", ""),
                "minPrincipal", p.getOrDefault("minPrincipal", ""),
                "maxPrincipal", p.getOrDefault("maxPrincipal", ""),
                "minNumberOfRepayments", p.getOrDefault("minNumberOfRepayments", ""),
                "maxNumberOfRepayments", p.getOrDefault("maxNumberOfRepayments", "")
        )).toList());
        out.put("activeProduct", active.toSummaryMap());
        return out;
    }

    public Map<String, Object> previewAdaptation(LoanApplication app, Integer productId) {
        LoanProductTemplate t = fineractClient.getLoanProductTemplate(
                productId != null ? productId : fineractClient.getDefaultProductId());
        AdaptedLoanRequest adapted = fineractClient.adaptToProduct(app, t);

        Map<String, Object> out = new HashMap<>();
        out.put("product", t.toSummaryMap());
        out.put("requestedPrincipal", app.getRequestedAmount());
        out.put("requestedTermMonths", app.getTermMonths());
        out.put("adaptedPrincipal", adapted.getPrincipal());
        out.put("adaptedNumberOfRepayments", adapted.getNumberOfRepayments());
        out.put("adjustments", adapted.getAdjustments());
        out.put("withinLimits", adapted.getAdjustments().size() == 1
                && adapted.getAdjustments().get(0).startsWith("No adjustments"));
        return out;
    }

    public Map<String, Object> brief(String question, String externalId, Long clientId) {
        Map<String, Object> portfolio = portfolio(externalId, clientId, 20);
        String context;
        try {
            context = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(portfolio);
        } catch (Exception e) {
            context = String.valueOf(portfolio);
        }

        String prompt = """
                You are an experienced Loan Officer working with Apache Fineract.
                Answer clearly and actionably. If recommending a new application,
                respect product min/max principal and numberOfRepayments.

                Portfolio / product data:
                %s

                Officer question:
                %s
                """.formatted(context, question != null ? question : "Summarize the current loan portfolio and active product limits.");

        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false,
                    "options", Map.of("temperature", 0.2)
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = ollama.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(120))
                    .block();
            String text = resp != null ? String.valueOf(resp.getOrDefault("response", "")) : "";
            return Map.of(
                    "role", "loan_officer",
                    "answer", text,
                    "portfolio", portfolio,
                    "llm", true
            );
        } catch (Exception e) {
            log.warn("Loan Officer LLM unavailable: {}", e.getMessage());
            return Map.of(
                    "role", "loan_officer",
                    "answer", heuristicBrief(portfolio),
                    "portfolio", portfolio,
                    "llm", false,
                    "llmError", e.getMessage()
            );
        }
    }

    private String heuristicBrief(Map<String, Object> portfolio) {
        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) portfolio.get("activeProduct");
        Object count = portfolio.get("loanCount");
        return "Loan Officer summary (LLM offline): "
                + count + " loan(s) listed. Active product id="
                + product.get("productId") + " name=" + product.get("name")
                + " principal range [" + product.get("minPrincipal") + " – " + product.get("maxPrincipal") + "]"
                + " repayments range [" + product.get("minNumberOfRepayments") + " – " + product.get("maxNumberOfRepayments") + "]."
                + " New applications will be auto-clamped to these limits before Fineract submit.";
    }
}
