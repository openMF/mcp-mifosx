package org.community.mifos.agentic.loan.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.community.mifos.agentic.loan.model.LoanDecision;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Local Ollama-powered decision agent using plain HTTP (no ollama4j dependency).
 * Works with any Ollama-compatible server at the configured base URL.
 */
@Component("ollamaAgentActivities")
@ActivityImpl(taskQueues = "loan-underwriter-queue")
public class OllamaAgentActivitiesImpl implements OllamaAgentActivities {

    private static final Logger log = LoggerFactory.getLogger(OllamaAgentActivitiesImpl.class);

    private final WebClient webClient;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaAgentActivitiesImpl(
            WebClient.Builder webClientBuilder,
            @Value("${loan.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${loan.ollama.model:llama3.2:latest}") String model) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .build();
        this.model = model;
    }

    @Override
    public LoanDecision aggregateAndDecide(Map<String, Object> context) {
        log.info("Calling local Ollama model={} for loan decision", model);

        String prompt = buildPrompt(context);
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);
            body.put("options", Map.of("temperature", 0.1));

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(180))
                    .block();

            String text = response != null ? String.valueOf(response.getOrDefault("response", "")) : "";
            log.debug("Ollama raw response: {}", text);
            LoanDecision decision = parseDecision(text);
            decision.setLlmThinking(text);
            decision.setLlmModel(model);
            return decision;
        } catch (Exception e) {
            log.error("Ollama call failed – falling back to heuristic decision: {}", e.getMessage());
            return heuristicFallback();
        }
    }

    private String buildPrompt(Map<String, Object> context) {
        return """
                You are an expert loan underwriter agent running on-premise.
                Analyse the following loan application data and return ONLY a valid JSON object
                with no additional text, markdown or explanation.

                Required JSON schema:
                {
                  "recommendation": "APPROVE" | "REJECT" | "REFER",
                  "summary": "concise 2-4 sentence rationale",
                  "riskLevel": "LOW" | "MEDIUM" | "HIGH"
                }

                Data:
                """ + toJsonSafe(context) + """

                Rules:
                - Prefer APPROVE only when income, expense and credit assessments all passed and risk is LOW/MEDIUM.
                - Prefer REJECT when credit score is weak or disposable income is insufficient.
                - Use REFER when data is incomplete or borderline.
                """;
    }

    private LoanDecision parseDecision(String raw) {
        try {
            String json = raw;
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = raw.substring(start, end + 1);
            }
            JsonNode node = mapper.readTree(json);
            LoanDecision.Recommendation rec = LoanDecision.Recommendation.valueOf(
                    node.path("recommendation").asText("REFER").toUpperCase());
            return LoanDecision.builder()
                    .recommendation(rec)
                    .summary(node.path("summary").asText("No summary provided"))
                    .riskLevel(node.path("riskLevel").asText("MEDIUM"))
                    .finalStatus("PENDING_REVIEW")
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse Ollama JSON, using REFER: {}", e.getMessage());
            return LoanDecision.builder()
                    .recommendation(LoanDecision.Recommendation.REFER)
                    .summary("LLM response could not be parsed: " + e.getMessage())
                    .riskLevel("MEDIUM")
                    .finalStatus("PENDING_REVIEW")
                    .build();
        }
    }

    private LoanDecision heuristicFallback() {
        return LoanDecision.builder()
                .recommendation(LoanDecision.Recommendation.REFER)
                .summary("Local LLM unavailable – referred for manual underwriting.")
                .riskLevel("MEDIUM")
                .finalStatus("PENDING_REVIEW")
                .llmThinking("LLM call failed or timed out; heuristic REFER applied.")
                .llmModel(model)
                .build();
    }

    private String toJsonSafe(Object obj) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
