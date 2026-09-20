package org.community.mifos.fineract;

import org.community.mifos.loan.model.LoanApplication;
import org.community.mifos.loan.model.LoanDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight Apache Fineract REST client.
 * Works against the public sandbox (https://sandbox.mifos.community) and any local Fineract instance.
 *
 * Auth: Basic + Fineract-Platform-TenantId header (default tenant "default").
 */
@Component
public class FineractClient {

    private static final Logger log = LoggerFactory.getLogger(FineractClient.class);

    /** Must use Locale.ENGLISH so month names are always "January", "September", etc. */
    private static final DateTimeFormatter FINERACT_DATE =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    private final WebClient webClient;
    private final int defaultProductId;
    private final int defaultOfficeId;

    public FineractClient(
            WebClient.Builder builder,
            @Value("${loan.fineract.base-url}") String baseUrl,
            @Value("${loan.fineract.username}") String username,
            @Value("${loan.fineract.password}") String password,
            @Value("${loan.fineract.tenant-id:default}") String tenantId,
            @Value("${loan.fineract.default-product-id:1}") int defaultProductId,
            @Value("${loan.fineract.default-office-id:1}") int defaultOfficeId) {

        String basic = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));

        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader("Fineract-Platform-TenantId", tenantId)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.defaultProductId = defaultProductId;
        this.defaultOfficeId = defaultOfficeId;

        log.info("FineractClient ready baseUrl={} productId={} officeId={}",
                baseUrl, defaultProductId, defaultOfficeId);
    }

    /**
     * Creates a client, submits a loan application, then approves it.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> submitAndApproveLoan(LoanApplication app, LoanDecision decision) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long clientId = createClient(app);
            result.put("clientId", clientId);
            log.info("Fineract client created clientId={}", clientId);

            Map<String, Object> loanPayload = buildLoanPayload(app, clientId);
            log.debug("Fineract loan submit payload: {}", loanPayload);

            Map<String, Object> submitResp = webClient.post()
                    .uri("/loans")
                    .bodyValue(loanPayload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            Long loanId = extractResourceId(submitResp);
            result.put("loanId", loanId);
            result.put("submitResponse", submitResp);
            log.info("Fineract loan submitted loanId={}", loanId);

            String approvedOn = formatDate(LocalDate.now());
            String expectedDisbursement = formatDate(LocalDate.now().plusDays(7));

            Map<String, Object> approveBody = new HashMap<>();
            approveBody.put("approvedOnDate", approvedOn);
            approveBody.put("expectedDisbursementDate", expectedDisbursement);
            approveBody.put("approvedLoanAmount", app.getRequestedAmount());
            approveBody.put("locale", "en");
            approveBody.put("dateFormat", "dd MMMM yyyy");

            log.debug("Fineract loan approve payload: {}", approveBody);

            Map<String, Object> approveResp = webClient.post()
                    .uri("/loans/{loanId}?command=approve", loanId)
                    .bodyValue(approveBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            result.put("approveResponse", approveResp);
            result.put("status", "APPROVED_IN_FINERACT");
            log.info("Fineract loan {} approved successfully", loanId);
            return result;

        } catch (WebClientResponseException e) {
            log.error("Fineract API error {} {}: {}",
                    e.getStatusCode(), e.getStatusCode().value(), e.getResponseBodyAsString());
            result.put("error", e.getResponseBodyAsString());
            result.put("statusCode", e.getStatusCode().value());
            result.put("status", "FINERACT_ERROR");
            return result;
        } catch (Exception e) {
            log.error("Fineract integration failed: {}", e.getMessage(), e);
            result.put("error", e.getMessage());
            result.put("status", "FINERACT_ERROR");
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private Long createClient(LoanApplication app) {
        String activationDate = formatDate(LocalDate.now());

        Map<String, Object> body = new HashMap<>();
        body.put("officeId", defaultOfficeId);
        body.put("firstname", firstName(app.getFullName()));
        body.put("lastname", lastName(app.getFullName()));
        body.put("legalFormId", 1);
        body.put("active", true);
        body.put("activationDate", activationDate);
        body.put("locale", "en");
        body.put("dateFormat", "dd MMMM yyyy");
        if (app.getEmail() != null && !app.getEmail().isBlank()) {
            body.put("emailAddress", app.getEmail());
        }
        if (app.getPhone() != null && !app.getPhone().isBlank()) {
            body.put("mobileNo", app.getPhone());
        }

        log.info("Fineract createClient payload: activationDate='{}' dateFormat='dd MMMM yyyy' locale=en firstname={} lastname={}",
                activationDate, body.get("firstname"), body.get("lastname"));
        log.debug("Full createClient body: {}", body);

        Map<String, Object> resp = webClient.post()
                .uri("/clients")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        log.debug("Fineract createClient response: {}", resp);
        return extractResourceId(resp);
    }

    private Map<String, Object> buildLoanPayload(LoanApplication app, Long clientId) {
        LocalDate today = LocalDate.now();
        int term = app.getTermMonths() != null ? app.getTermMonths() : 6;
        BigDecimal principal = app.getRequestedAmount();

        Map<String, Object> payload = new HashMap<>();
        payload.put("clientId", clientId);
        payload.put("productId", defaultProductId);
        payload.put("principal", principal);
        payload.put("loanTermFrequency", term);
        payload.put("loanTermFrequencyType", 0);          // 0 = months        
        payload.put("numberOfRepayments", term);          // Dynamically match numberOfRepayments to the term length
        payload.put("repaymentEvery", 1);
        payload.put("repaymentFrequencyType", 0);         // 0 = months
        
        payload.put("interestRatePerPeriod", 0);
        payload.put("amortizationType", 1);               // 1 = equal installments
        payload.put("interestType", 1);                   // 1 = declining balance
        payload.put("interestCalculationPeriodType", 1);  // 1 = same as repayment period        
        payload.put("expectedDisbursementDate", formatDate(today.plusDays(7)));
        payload.put("submittedOnDate", formatDate(today));
        payload.put("loanType", "individual");
        payload.put("transactionProcessingStrategyCode", "mifos-standard-strategy");
        payload.put("locale", "en");
        payload.put("dateFormat", "dd MMMM yyyy");
        
        return payload;
    }

    /** Always English month names, e.g. "19 September 2026". */
    private static String formatDate(LocalDate date) {
        return date.format(FINERACT_DATE);
    }

    private Long extractResourceId(Map<String, Object> resp) {
        if (resp == null) return null;
        Object id = resp.get("resourceId");
        if (id == null) id = resp.get("loanId");
        if (id == null) id = resp.get("clientId");
        if (id instanceof Number n) return n.longValue();
        return id != null ? Long.parseLong(id.toString()) : null;
    }

    private String firstName(String full) {
        if (full == null || full.isBlank()) return "Applicant";
        String[] parts = full.trim().split("\\s+", 2);
        return parts[0];
    }

    private String lastName(String full) {
        if (full == null || full.isBlank()) return "Unknown";
        String[] parts = full.trim().split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "Unknown";
    }
}
