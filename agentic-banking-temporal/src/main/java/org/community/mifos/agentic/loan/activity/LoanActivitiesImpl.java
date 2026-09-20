package org.community.mifos.agentic.loan.activity;

import org.community.mifos.agentic.loan.model.AssessmentResult;
import org.community.mifos.agentic.loan.model.LoanApplication;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component("loanActivities")
@ActivityImpl(taskQueues = "loan-underwriter-queue")
public class LoanActivitiesImpl implements LoanActivities {

    private static final Logger log = LoggerFactory.getLogger(LoanActivitiesImpl.class);

    private final WebClient webClient;
    private final boolean mockEnabled;

    public LoanActivitiesImpl(
            WebClient.Builder webClientBuilder,
            @Value("${loan.mock-data.enabled:true}") boolean mockEnabled,
            @Value("${loan.mock-data.bank-base-url:http://localhost:3233}") String bankBaseUrl) {
        this.webClient = webClientBuilder.baseUrl(bankBaseUrl).build();
        this.mockEnabled = mockEnabled;
    }

    @Override
    public Map<String, Object> fetchBankAccount(String applicantId) {
        log.info("Fetching bank account for {}", applicantId);
        if (mockEnabled) {
            return mockBank(applicantId);
        }
        try {
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/bank").queryParam("applicant_id", applicantId).build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            log.warn("Live bank fetch failed, using mock: {}", e.getMessage());
            return mockBank(applicantId);
        }
    }

    @Override
    public Map<String, Object> fetchCreditReportCibil(String applicantId) {
        log.info("Fetching CIBIL credit report for {}", applicantId);
        // Simulate occasional failure to exercise Temporal fallback
        if (ThreadLocalRandom.current().nextInt(100) < 15) {
            throw new RuntimeException("CIBIL provider temporarily unavailable");
        }
        return mockCredit(applicantId, "CIBIL", 720);
    }

    @Override
    public Map<String, Object> fetchCreditReportExperian(String applicantId) {
        log.info("Fetching Experian credit report for {}", applicantId);
        return mockCredit(applicantId, "Experian", 695);
    }

    @Override
    public Map<String, Object> processDocument(String path, String applicantId) {
        log.info("Processing document {} for {}", path, applicantId);
        // On-premise: replace Bedrock Nova with local structured extraction stub.
        // In production plug in a local OCR (e.g. Tesseract + Ollama vision model) here.
        Map<String, Object> result = new HashMap<>();
        result.put("path", path);
        result.put("applicantId", applicantId);
        result.put("extracted", Map.of(
                "type", inferDocType(path),
                "confidence", 0.92,
                "fields", Map.of("status", "extracted-locally")
        ));
        return result;
    }

    @Override
    public AssessmentResult incomeAssessment(LoanApplication app, Map<String, Object> bank, Map<String, Object> credit) {
        BigDecimal monthlyIncome = extractBigDecimal(bank, "monthlyIncome", new BigDecimal("4500"));
        BigDecimal requested = app.getRequestedAmount();
        // Simple affordability heuristic: income should support ~3x annualised request or better
        BigDecimal ratio = monthlyIncome.multiply(BigDecimal.valueOf(12))
                .divide(requested, 2, RoundingMode.HALF_UP);
        boolean passed = ratio.compareTo(new BigDecimal("2.5")) >= 0;
        return AssessmentResult.builder()
                .type("income")
                .passed(passed)
                .score(ratio)
                .reason(passed ? "Income sufficient relative to requested amount" : "Income-to-loan ratio too low")
                .details(Map.of("monthlyIncome", monthlyIncome, "ratio", ratio))
                .build();
    }

    @Override
    public AssessmentResult expenseAssessment(LoanApplication app, Map<String, Object> bank) {
        BigDecimal monthlyIncome = extractBigDecimal(bank, "monthlyIncome", new BigDecimal("4500"));
        BigDecimal monthlyExpenses = extractBigDecimal(bank, "monthlyExpenses", new BigDecimal("2800"));
        BigDecimal disposable = monthlyIncome.subtract(monthlyExpenses);
        boolean passed = disposable.compareTo(new BigDecimal("800")) > 0;
        return AssessmentResult.builder()
                .type("expense")
                .passed(passed)
                .score(disposable)
                .reason(passed ? "Adequate disposable income" : "High expense burden")
                .details(Map.of("monthlyExpenses", monthlyExpenses, "disposable", disposable))
                .build();
    }

    @Override
    public AssessmentResult creditAssessment(LoanApplication app, Map<String, Object> credit) {
        int score = ((Number) credit.getOrDefault("score", 650)).intValue();
        boolean passed = score >= 620;
        return AssessmentResult.builder()
                .type("credit")
                .passed(passed)
                .score(BigDecimal.valueOf(score))
                .reason(passed ? "Credit score meets threshold" : "Credit score below 620")
                .details(Map.of("provider", credit.getOrDefault("provider", "unknown"), "score", score))
                .build();
    }

    // ---------- helpers ----------

    private Map<String, Object> mockBank(String applicantId) {
        Map<String, Object> m = new HashMap<>();
        m.put("applicantId", applicantId);
        m.put("accountNumber", "****" + applicantId.hashCode() % 10000);
        m.put("monthlyIncome", 5200);
        m.put("monthlyExpenses", 3100);
        m.put("balance", 12500);
        m.put("currency", "USD");
        return m;
    }

    private Map<String, Object> mockCredit(String applicantId, String provider, int score) {
        Map<String, Object> m = new HashMap<>();
        m.put("applicantId", applicantId);
        m.put("provider", provider);
        m.put("score", score);
        m.put("delinquencies", 0);
        m.put("openAccounts", 4);
        return m;
    }

    private String inferDocType(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("bank") || lower.contains("statement")) return "BANK_STATEMENT";
        if (lower.contains("id") || lower.contains("license") || lower.contains("passport")) return "ID";
        if (lower.contains("salary") || lower.contains("payslip") || lower.contains("income")) return "INCOME";
        return "OTHER";
    }

    private BigDecimal extractBigDecimal(Map<String, Object> map, String key, BigDecimal def) {
        Object v = map.get(key);
        if (v == null) return def;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (Exception e) {
            return def;
        }
    }
}
