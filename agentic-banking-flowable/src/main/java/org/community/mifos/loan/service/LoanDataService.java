package org.community.mifos.loan.service;

import org.community.mifos.loan.model.AssessmentResult;
import org.community.mifos.loan.model.LoanApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class LoanDataService {

    private static final Logger log = LoggerFactory.getLogger(LoanDataService.class);

    private final WebClient webClient;
    private final boolean mockEnabled;

    public LoanDataService(
            WebClient.Builder builder,
            @Value("${loan.mock-data.enabled:true}") boolean mockEnabled,
            @Value("${loan.mock-data.bank-base-url:http://localhost:3233}") String bankBaseUrl) {
        this.webClient = builder.baseUrl(bankBaseUrl).build();
        this.mockEnabled = mockEnabled;
    }

    public Map<String, Object> fetchBankAccount(String applicantId) {
        log.info("Fetching bank account for {}", applicantId);
        if (mockEnabled) return mockBank(applicantId);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = webClient.get()
                    .uri(ub -> ub.path("/bank").queryParam("applicant_id", applicantId).build())
                    .retrieve().bodyToMono(Map.class).block();
            return r != null ? r : mockBank(applicantId);
        } catch (Exception e) {
            log.warn("Live bank fetch failed, using mock: {}", e.getMessage());
            return mockBank(applicantId);
        }
    }

    public Map<String, Object> fetchCreditReport(String applicantId) {
        try {
            if (ThreadLocalRandom.current().nextInt(100) < 15) {
                throw new RuntimeException("CIBIL provider temporarily unavailable");
            }
            log.info("CIBIL credit report for {}", applicantId);
            return mockCredit(applicantId, "CIBIL", 720);
        } catch (Exception e) {
            log.warn("CIBIL failed, falling back to Experian: {}", e.getMessage());
            return mockCredit(applicantId, "Experian", 695);
        }
    }

    public AssessmentResult incomeAssessment(LoanApplication app, Map<String, Object> bank) {
        BigDecimal monthlyIncome = extractBd(bank, "monthlyIncome", new BigDecimal("4500"));
        BigDecimal requested = app.getRequestedAmount();
        BigDecimal ratio = monthlyIncome.multiply(BigDecimal.valueOf(12))
                .divide(requested, 2, RoundingMode.HALF_UP);
        boolean passed = ratio.compareTo(new BigDecimal("2.5")) >= 0;
        return AssessmentResult.builder()
                .type("income").passed(passed).score(ratio)
                .reason(passed ? "Income sufficient" : "Income-to-loan ratio too low")
                .details(Map.of("monthlyIncome", monthlyIncome, "ratio", ratio))
                .build();
    }

    public AssessmentResult expenseAssessment(LoanApplication app, Map<String, Object> bank) {
        BigDecimal monthlyIncome = extractBd(bank, "monthlyIncome", new BigDecimal("4500"));
        BigDecimal monthlyExpenses = extractBd(bank, "monthlyExpenses", new BigDecimal("2800"));
        BigDecimal disposable = monthlyIncome.subtract(monthlyExpenses);
        boolean passed = disposable.compareTo(new BigDecimal("800")) > 0;
        return AssessmentResult.builder()
                .type("expense").passed(passed).score(disposable)
                .reason(passed ? "Adequate disposable income" : "High expense burden")
                .details(Map.of("monthlyExpenses", monthlyExpenses, "disposable", disposable))
                .build();
    }

    public AssessmentResult creditAssessment(Map<String, Object> credit) {
        int score = ((Number) credit.getOrDefault("score", 650)).intValue();
        boolean passed = score >= 620;
        return AssessmentResult.builder()
                .type("credit").passed(passed).score(BigDecimal.valueOf(score))
                .reason(passed ? "Credit score meets threshold" : "Credit score below 620")
                .details(Map.of("provider", credit.getOrDefault("provider", "unknown"), "score", score))
                .build();
    }

    public List<AssessmentResult> runAllAssessments(LoanApplication app,
                                                    Map<String, Object> bank,
                                                    Map<String, Object> credit) {
        return List.of(
                incomeAssessment(app, bank),
                expenseAssessment(app, bank),
                creditAssessment(credit)
        );
    }

    private Map<String, Object> mockBank(String applicantId) {
        Map<String, Object> m = new HashMap<>();
        m.put("applicantId", applicantId);
        m.put("accountNumber", "****" + Math.abs(applicantId.hashCode() % 10000));
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

    private BigDecimal extractBd(Map<String, Object> map, String key, BigDecimal def) {
        Object v = map.get(key);
        if (v == null) return def;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return def; }
    }
}
