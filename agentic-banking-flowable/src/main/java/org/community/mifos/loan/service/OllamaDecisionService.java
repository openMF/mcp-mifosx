package org.community.mifos.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.community.mifos.loan.model.LoanDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaDecisionService {

    private static final Logger log = LoggerFactory.getLogger(OllamaDecisionService.class);

    private final WebClient webClient;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaDecisionService(
            WebClient.Builder builder,
            @Value("${loan.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${loan.ollama.model:llama3.2:latest}") String model) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.model = model;
    }

    public LoanDecision decide(Map<String, Object> context) {
        log.info("Calling Ollama model={} for decision", model);
        String prompt = """
                You are an expert loan underwriter agent running on-premise.
                Return ONLY valid JSON (no markdown):
                {"recommendation":"APPROVE"|"REJECT"|"REFER","summary":"...","riskLevel":"LOW"|"MEDIUM"|"HIGH"}

                Data:
                """ + toJson(context) + """

                Rules: APPROVE when assessments mostly passed; REJECT on weak credit/income; REFER if borderline.
                """;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);
            body.put("options", Map.of("temperature", 0.1));

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = webClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(180))
                    .block();

            String text = resp != null ? String.valueOf(resp.getOrDefault("response", "")) : "";
            return parse(text);
        } catch (Exception e) {
            log.error("Ollama failed, heuristic REFER: {}", e.getMessage());
            return LoanDecision.builder()
                    .recommendation(LoanDecision.Recommendation.REFER)
                    .summary("Local LLM unavailable – referred for manual underwriting.")
                    .riskLevel("MEDIUM")
                    .finalStatus("PENDING_REVIEW")
                    .build();
        }
    }

    private LoanDecision parse(String raw) {
        try {
            int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
            String json = (s >= 0 && e > s) ? raw.substring(s, e + 1) : raw;
            JsonNode n = mapper.readTree(json);
            return LoanDecision.builder()
                    .recommendation(LoanDecision.Recommendation.valueOf(
                            n.path("recommendation").asText("REFER").toUpperCase()))
                    .summary(n.path("summary").asText("No summary"))
                    .riskLevel(n.path("riskLevel").asText("MEDIUM"))
                    .finalStatus("PENDING_REVIEW")
                    .build();
        } catch (Exception ex) {
            return LoanDecision.builder()
                    .recommendation(LoanDecision.Recommendation.REFER)
                    .summary("Parse error: " + ex.getMessage())
                    .riskLevel("MEDIUM")
                    .finalStatus("PENDING_REVIEW")
                    .build();
        }
    }

    private String toJson(Object o) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(o); }
        catch (Exception e) { return String.valueOf(o); }
    }
}
