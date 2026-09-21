/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.agentic.loan.fineract;

import org.community.mifos.agentic.loan.model.AdaptedLoanRequest;
import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import org.community.mifos.agentic.loan.model.LoanProductTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apache Fineract REST client with product-template adaptation.
 * Fetches loan product constraints and clamps principal / term before submit
 * so applications stay portable across different loan products.
 */
@Component
public class FineractClient {

    private static final Logger log = LoggerFactory.getLogger(FineractClient.class);

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

    // ── Product template ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public LoanProductTemplate getLoanProductTemplate(Integer productId) {
        int id = productId != null ? productId : defaultProductId;
        try {
            Map<String, Object> raw = webClient.get()
                    .uri("/loanproducts/{id}", id)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return mapProduct(raw, id);
        } catch (WebClientResponseException e) {
            log.error("Failed to fetch loan product {}: {} {}", id, e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("Cannot load loan product " + id + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listLoanProducts() {
        try {
            Object body = webClient.get()
                    .uri("/loanproducts")
                    .retrieve()
                    .bodyToMono(Object.class)
                    .block();
            if (body instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map) out.add((Map<String, Object>) o);
                }
                return out;
            }
            return List.of();
        } catch (WebClientResponseException e) {
            log.error("listLoanProducts failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return List.of();
        }
    }

    /**
     * Clamp application fields to the product min/max so Fineract validation succeeds.
     */
    public AdaptedLoanRequest adaptToProduct(LoanApplication app, LoanProductTemplate t) {
        AdaptedLoanRequest a = new AdaptedLoanRequest();
        a.setProductId(t.getProductId());
        a.setProductName(t.getName());

        BigDecimal principal = app.getRequestedAmount() != null
                ? app.getRequestedAmount() : t.getPrincipal();
        if (principal == null) principal = BigDecimal.valueOf(1000);

        if (t.getMinPrincipal() != null && principal.compareTo(t.getMinPrincipal()) < 0) {
            a.addAdjustment("principal " + principal + " below min " + t.getMinPrincipal() + " → clamped");
            principal = t.getMinPrincipal();
        }
        if (t.getMaxPrincipal() != null && principal.compareTo(t.getMaxPrincipal()) > 0) {
            a.addAdjustment("principal " + principal + " above max " + t.getMaxPrincipal() + " → clamped");
            principal = t.getMaxPrincipal();
        }
        a.setPrincipal(principal.setScale(2, RoundingMode.HALF_UP));

        int repayments = app.getTermMonths() != null ? app.getTermMonths()
                : (t.getNumberOfRepayments() != null ? t.getNumberOfRepayments() : 12);
        if (t.getMinNumberOfRepayments() != null && repayments < t.getMinNumberOfRepayments()) {
            a.addAdjustment("numberOfRepayments " + repayments + " below min " + t.getMinNumberOfRepayments() + " → clamped");
            repayments = t.getMinNumberOfRepayments();
        }
        if (t.getMaxNumberOfRepayments() != null && repayments > t.getMaxNumberOfRepayments()) {
            a.addAdjustment("numberOfRepayments " + repayments + " above max " + t.getMaxNumberOfRepayments() + " → clamped");
            repayments = t.getMaxNumberOfRepayments();
        }
        a.setNumberOfRepayments(repayments);
        a.setLoanTermFrequency(repayments);
        a.setLoanTermFrequencyType(t.getLoanTermFrequencyType() != null ? t.getLoanTermFrequencyType() : 2); // months
        a.setRepaymentEvery(t.getRepaymentEvery() != null ? t.getRepaymentEvery() : 1);
        a.setRepaymentFrequencyType(t.getRepaymentFrequencyType() != null ? t.getRepaymentFrequencyType() : 2);
        a.setInterestRatePerPeriod(t.getInterestRatePerPeriod() != null ? t.getInterestRatePerPeriod() : BigDecimal.ZERO);

        if (a.getAdjustments().isEmpty()) {
            a.addAdjustment("No adjustments – request already within product limits");
        }
        log.info("Adapted loan for product={} principal={} repayments={} adjustments={}",
                t.getName(), a.getPrincipal(), a.getNumberOfRepayments(), a.getAdjustments());
        return a;
    }

    // ── Existing loans (Loan Officer view) ──────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchLoans(String externalId, Long clientId, int limit) {
        try {
            StringBuilder q = new StringBuilder("/loans?limit=").append(Math.max(1, Math.min(limit, 100)));
            if (externalId != null && !externalId.isBlank()) {
                q.append("&externalId=").append(externalId);
            }
            if (clientId != null) {
                q.append("&clientId=").append(clientId);
            }
            Object body = webClient.get()
                    .uri(q.toString())
                    .retrieve()
                    .bodyToMono(Object.class)
                    .block();

            List<Map<String, Object>> out = new ArrayList<>();
            if (body instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map) out.add(summarizeLoan((Map<String, Object>) o));
                }
            } else if (body instanceof Map<?, ?> page) {
                Object pageItems = page.get("pageItems");
                if (pageItems instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map) out.add(summarizeLoan((Map<String, Object>) o));
                    }
                }
            }
            return out;
        } catch (WebClientResponseException e) {
            log.error("searchLoans failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return List.of(Map.of("error", e.getMessage(), "body", e.getResponseBodyAsString()));
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLoanByExternalId(String externalId) {
        List<Map<String, Object>> found = searchLoans(externalId, null, 10);
        if (found.isEmpty()) return Map.of("found", false, "externalId", externalId);
        return found.get(0);
    }

    // ── Create client + loan + approve ──────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> submitAndApproveLoan(LoanApplication app, LoanDecision decision,
                                                   List<Map<String, Object>> documentResults) {
        Map<String, Object> result = new HashMap<>();
        try {
            LoanProductTemplate template = getLoanProductTemplate(defaultProductId);
            AdaptedLoanRequest adapted = adaptToProduct(app, template);
            result.put("product", template.toSummaryMap());
            result.put("adaptations", adapted.getAdjustments());

            String curpClave = extractValidCurpClave(documentResults);
            List<String> validCurpPaths = extractValidCurpPaths(documentResults);
            if (curpClave != null) {
                result.put("curpClave", curpClave);
                log.info("Valid CURP will be used as client externalId={}", curpClave);
            }

            Long clientId = createClient(app, curpClave);
            result.put("clientId", clientId);
            log.info("Fineract client created clientId={} externalId={}", clientId, curpClave);

            Map<String, Object> loanPayload = buildLoanPayload(app, clientId, template, adapted);
            log.debug("Fineract loan submit payload: {}", loanPayload);

            Map<String, Object> submitResp = webClient.post()
                    .uri("/loans")
                    .bodyValue(loanPayload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            Long loanId = extractResourceId(submitResp);
            result.put("loanId", loanId);
            result.put("externalId", app.getWorkflowId());
            result.put("principalSubmitted", adapted.getPrincipal());
            result.put("numberOfRepaymentsSubmitted", adapted.getNumberOfRepayments());
            result.put("submitResponse", submitResp);
            log.info("Fineract loan submitted loanId={} externalId={} principal={}",
                    loanId, app.getWorkflowId(), adapted.getPrincipal());

            String approvedOn = formatDate(LocalDate.now());
            String expectedDisbursement = formatDate(LocalDate.now().plusDays(7));

            Map<String, Object> approveBody = new HashMap<>();
            approveBody.put("approvedOnDate", approvedOn);
            approveBody.put("expectedDisbursementDate", expectedDisbursement);
            approveBody.put("approvedLoanAmount", adapted.getPrincipal());
            approveBody.put("locale", "en");
            approveBody.put("dateFormat", "dd MMMM yyyy");

            Map<String, Object> approveResp = webClient.post()
                    .uri("/loans/{loanId}?command=approve", loanId)
                    .bodyValue(approveBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            result.put("approveResponse", approveResp);
            result.put("status", "APPROVED_IN_FINERACT");
            log.info("Fineract loan {} approved successfully", loanId);

            // Note 1 (independent): underwriting decision from OLLAMA_MODEL (text LLM)
            try {
                String decisionNoteId = addLoanNote(loanId, buildDecisionNote(app, decision, adapted));
                result.put("decisionNoteId", decisionNoteId);
                result.put("decisionNoteAttached", true);
            } catch (Exception noteEx) {
                log.warn("Could not attach decision note to loan {}: {}", loanId, noteEx.getMessage());
                result.put("decisionNoteAttached", false);
                result.put("decisionNoteError", noteEx.getMessage());
            }

            // Note 2 (independent): vision-model document analysis from OLLAMA_VISION_MODEL
            try {
                String visionNoteText = buildVisionNote(app, documentResults);
                if (visionNoteText != null && !visionNoteText.isBlank()) {
                    String visionNoteId = addLoanNote(loanId, visionNoteText);
                    result.put("visionNoteId", visionNoteId);
                    result.put("visionNoteAttached", true);
                } else {
                    result.put("visionNoteAttached", false);
                    result.put("visionNoteSkipped", "no vision analysis available");
                }
            } catch (Exception visionNoteEx) {
                log.warn("Could not attach vision note to loan {}: {}", loanId, visionNoteEx.getMessage());
                result.put("visionNoteAttached", false);
                result.put("visionNoteError", visionNoteEx.getMessage());
            }

            // Valid CURP files → Fineract loan documents
            // Multipart form (Mifos UI): name=filename, description=clave,
            // dateFormat=yyyy-MM-dd, locale=es, issuanceDate=yyyy-MM-dd
            String curpIssueDate = extractValidCurpIssueDate(documentResults);
            if (curpIssueDate != null) {
                result.put("curpIssueDate", curpIssueDate);
            }
            List<Map<String, Object>> uploadedDocs = new ArrayList<>();
            for (String docPath : validCurpPaths) {
                try {
                    Map<String, Object> up = uploadLoanDocument(
                            loanId,
                            docPath,
                            null,            // name → original filename
                            curpClave,       // description → CURP clave
                            curpIssueDate);  // issuanceDate from vision
                    uploadedDocs.add(up);
                } catch (Exception docEx) {
                    log.warn("Loan document upload failed for {}: {}", docPath, docEx.getMessage());
                    uploadedDocs.add(Map.of("path", docPath, "error", docEx.getMessage()));
                }
            }
            if (!uploadedDocs.isEmpty()) {
                result.put("loanDocuments", uploadedDocs);
            }
            return result;

        } catch (WebClientResponseException e) {
            log.error("Fineract API error {} {}: {}", e.getStatusCode(), e.getStatusText(), e.getResponseBodyAsString());
            result.put("status", "FINERACT_ERROR");
            result.put("error", e.getResponseBodyAsString());
            result.put("httpStatus", e.getStatusCode().value());
            return result;
        } catch (Exception e) {
            log.error("Fineract unexpected error: {}", e.getMessage(), e);
            result.put("status", "FINERACT_ERROR");
            result.put("error", e.getMessage());
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private Long createClient(LoanApplication app, String curpClave) {
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
        // Prefer validated CURP clave as client externalId (traceability to identity doc)
        if (curpClave != null && !curpClave.isBlank()) {
            body.put("externalId", curpClave);
        } else if (app.getWorkflowId() != null && !app.getWorkflowId().isBlank()) {
            body.put("externalId", app.getWorkflowId() + "-client");
        }
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

        return extractResourceId(resp);
    }

    private Map<String, Object> buildLoanPayload(LoanApplication app, Long clientId,
                                                 LoanProductTemplate t, AdaptedLoanRequest adapted) {
        LocalDate today = LocalDate.now();

        Map<String, Object> payload = new HashMap<>();
        payload.put("clientId", clientId);
        payload.put("productId", t.getProductId() != null ? t.getProductId() : defaultProductId);
        payload.put("principal", adapted.getPrincipal());
        payload.put("loanTermFrequency", adapted.getLoanTermFrequency());
        payload.put("loanTermFrequencyType", adapted.getLoanTermFrequencyType());
        payload.put("numberOfRepayments", adapted.getNumberOfRepayments());
        payload.put("repaymentEvery", adapted.getRepaymentEvery());
        payload.put("repaymentFrequencyType", adapted.getRepaymentFrequencyType());
        payload.put("interestRatePerPeriod", adapted.getInterestRatePerPeriod());
        payload.put("amortizationType", t.getAmortizationType() != null ? t.getAmortizationType() : 1);
        payload.put("interestType", t.getInterestType() != null ? t.getInterestType() : 0);
        payload.put("interestCalculationPeriodType",
                t.getInterestCalculationPeriodType() != null ? t.getInterestCalculationPeriodType() : 1);
        payload.put("expectedDisbursementDate", formatDate(today.plusDays(7)));
        payload.put("submittedOnDate", formatDate(today));
        payload.put("loanType", "individual");
        payload.put("transactionProcessingStrategyCode",
                t.getTransactionProcessingStrategyCode() != null
                        ? t.getTransactionProcessingStrategyCode()
                        : "mifos-standard-strategy");
        payload.put("locale", "en");
        payload.put("dateFormat", "dd MMMM yyyy");

        String externalId = app.getWorkflowId();
        if (externalId != null && !externalId.isBlank()) {
            payload.put("externalId", externalId);
            log.info("Fineract loan externalId set to workflowId={}", externalId);
        }
        return payload;
    }

    private LoanProductTemplate mapProduct(Map<String, Object> raw, int id) {
        LoanProductTemplate t = new LoanProductTemplate();
        t.setProductId(id);
        t.setRaw(raw);
        if (raw == null) return t;

        t.setName(str(raw.get("name")));
        t.setShortName(str(raw.get("shortName")));
        t.setMinPrincipal(bd(raw.get("minPrincipal")));
        t.setMaxPrincipal(bd(raw.get("maxPrincipal")));
        t.setPrincipal(bd(raw.get("principal")));
        t.setMinNumberOfRepayments(integer(raw.get("minNumberOfRepayments")));
        t.setMaxNumberOfRepayments(integer(raw.get("maxNumberOfRepayments")));
        t.setNumberOfRepayments(integer(raw.get("numberOfRepayments")));
        t.setRepaymentEvery(integer(raw.get("repaymentEvery")));
        t.setRepaymentFrequencyType(codeOrInt(raw.get("repaymentFrequencyType")));
        t.setInterestRatePerPeriod(bd(raw.get("interestRatePerPeriod")));
        t.setAmortizationType(codeOrInt(raw.get("amortizationType")));
        t.setInterestType(codeOrInt(raw.get("interestType")));
        t.setInterestCalculationPeriodType(codeOrInt(raw.get("interestCalculationPeriodType")));
        t.setLoanTermFrequency(integer(raw.get("numberOfRepayments")));
        t.setLoanTermFrequencyType(codeOrInt(raw.get("repaymentFrequencyType")));

        Object strategy = raw.get("transactionProcessingStrategyCode");
        if (strategy == null && raw.get("transactionProcessingStrategy") instanceof Map<?, ?> m) {
            strategy = m.get("code");
        }
        t.setTransactionProcessingStrategyCode(strategy != null ? strategy.toString() : "mifos-standard-strategy");
        return t;
    }

    private Map<String, Object> summarizeLoan(Map<String, Object> loan) {
        Map<String, Object> s = new HashMap<>();
        s.put("id", loan.get("id"));
        s.put("accountNo", loan.get("accountNo"));
        s.put("externalId", loan.get("externalId"));
        s.put("clientId", loan.get("clientId"));
        s.put("clientName", loan.get("clientName"));
        s.put("loanProductId", loan.get("loanProductId"));
        s.put("loanProductName", loan.get("loanProductName"));
        s.put("principal", loan.get("principal"));
        s.put("approvedPrincipal", loan.get("approvedPrincipal"));
        if (loan.get("status") instanceof Map<?, ?> st) {
            s.put("status", st.get("value"));
            s.put("statusCode", st.get("code"));
        } else {
            s.put("status", loan.get("status"));
        }
        s.put("timeline", loan.get("timeline"));
        return s;
    }

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

    private static BigDecimal bd(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }

    private static Integer integer(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }

    /** Fineract often returns {id:2, code:"...", value:"Months"} for enums. */
    private static Integer codeOrInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof Map<?, ?> m) {
            Object id = m.get("id");
            if (id instanceof Number n) return n.intValue();
        }
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }


    /**
     * POST /loans/{loanId}/notes – stores underwriter / LLM rationale on the loan account.
     */
    @SuppressWarnings("unchecked")
    public String addLoanNote(Long loanId, String noteText) {
        if (loanId == null || noteText == null || noteText.isBlank()) {
            return null;
        }
        // Fineract note length is limited in practice; keep a safe bound
        String note = noteText.length() > 4000 ? noteText.substring(0, 3997) + "..." : noteText;

        Map<String, Object> body = new HashMap<>();
        body.put("note", note);

        log.info("Attaching decision note to Fineract loanId={} ({} chars)", loanId, note.length());
        Map<String, Object> resp = webClient.post()
                .uri("/loans/{loanId}/notes", loanId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        Object resourceId = resp != null ? resp.get("resourceId") : null;
        log.debug("Loan note response: {}", resp);
        return resourceId != null ? resourceId.toString() : null;
    }

    /**
     * Loan note #1 – underwriting decision produced by the text LLM (OLLAMA_MODEL).
     * Independent from the vision-model note.
     */
    private String buildDecisionNote(LoanApplication app, LoanDecision decision, AdaptedLoanRequest adapted) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Agentic underwriting decision (OLLAMA_MODEL / Temporal) ===\n");
        if (app != null && app.getWorkflowId() != null) {
            sb.append("Workflow ID (loan externalId): ").append(app.getWorkflowId()).append("\n");
        }
        if (decision != null) {
            if (decision.getLlmModel() != null) {
                sb.append("LLM model (OLLAMA_MODEL): ").append(decision.getLlmModel()).append("\n");
            }
            if (decision.getRecommendation() != null) {
                sb.append("AI recommendation: ").append(decision.getRecommendation()).append("\n");
            }
            if (decision.getRiskLevel() != null) {
                sb.append("Risk level: ").append(decision.getRiskLevel()).append("\n");
            }
            if (decision.getHumanDecision() != null) {
                sb.append("Human decision: ").append(decision.getHumanDecision()).append("\n");
            }
            if (decision.getFinalStatus() != null) {
                sb.append("Final status: ").append(decision.getFinalStatus()).append("\n");
            }
            sb.append("\n--- AI summary ---\n");
            sb.append(decision.getSummary() != null ? decision.getSummary() : "(none)").append("\n");
            if (decision.getLlmThinking() != null && !decision.getLlmThinking().isBlank()) {
                sb.append("\n--- LLM thinking / raw output ---\n");
                sb.append(decision.getLlmThinking()).append("\n");
            }
            if (decision.getAssessments() != null && !decision.getAssessments().isEmpty()) {
                sb.append("\n--- Assessments ---\n");
                decision.getAssessments().forEach(a ->
                        sb.append(String.format("- %s: passed=%s score=%s reason=%s%n",
                                a.getType(), a.isPassed(), a.getScore(), a.getReason())));
            }
        }
        if (adapted != null) {
            sb.append("\n--- Product adaptation ---\n");
            sb.append("Principal submitted: ").append(adapted.getPrincipal()).append("\n");
            sb.append("Repayments: ").append(adapted.getNumberOfRepayments()).append("\n");
            if (adapted.getAdjustments() != null) {
                adapted.getAdjustments().forEach(adj -> sb.append("  * ").append(adj).append("\n"));
            }
        }
        return sb.toString();
    }

    /**
     * Loan note #2 – document analysis produced by the vision model (OLLAMA_VISION_MODEL).
     * Completely independent of the underwriting decision note.
     * Returns null/blank when there is no vision output to attach.
     */
    @SuppressWarnings("unchecked")
    private String buildVisionNote(LoanApplication app, List<Map<String, Object>> documentResults) {
        if (documentResults == null || documentResults.isEmpty()) {
            return null;
        }
        boolean anyVision = false;
        for (Map<String, Object> doc : documentResults) {
            Object extracted = doc.get("extracted");
            if (extracted instanceof Map<?, ?> em) {
                Object vj = em.get("visionJson");
                if (vj != null && !vj.toString().isBlank()) {
                    anyVision = true;
                    break;
                }
            }
            // Also treat structured CURP extraction as vision-driven content
            if (doc.get("docType") != null || extracted != null) {
                anyVision = true;
                break;
            }
        }
        if (!anyVision) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Vision model document analysis (OLLAMA_VISION_MODEL) ===\n");
        if (app != null && app.getWorkflowId() != null) {
            sb.append("Workflow ID (loan externalId): ").append(app.getWorkflowId()).append("\n");
        }
        sb.append("Source: CurpDocumentAgent (PDF → PNG → Ollama vision)\n");
        sb.append("This note is independent of the underwriting (OLLAMA_MODEL) decision note.\n\n");

        int idx = 0;
        for (Map<String, Object> doc : documentResults) {
            idx++;
            Object path = doc.get("path");
            Object docType = doc.get("docType");
            Object valid = doc.get("valid");
            sb.append(String.format("--- Document #%d ---%n", idx));
            if (path != null) sb.append("Path: ").append(path).append("\n");
            if (docType != null) sb.append("Type: ").append(docType).append("\n");
            if (valid != null) sb.append("Overall valid: ").append(valid).append("\n");
            Object msgs = doc.get("validationMessages");
            if (msgs instanceof List<?> list && !list.isEmpty()) {
                sb.append("Validation messages:\n");
                for (Object m : list) {
                    sb.append("  - ").append(m).append("\n");
                }
            }
            Object extracted = doc.get("extracted");
            if (extracted instanceof Map<?, ?> em) {
                Object clave = em.get("curpClave");
                Object name = em.get("fullName");
                Object issue = em.get("issueDate");
                Object conf = em.get("confidence");
                Object reg = em.get("registrationEntity");
                if (clave != null) sb.append("CURP clave: ").append(clave).append("\n");
                if (name != null) sb.append("Extracted name: ").append(name).append("\n");
                if (issue != null) sb.append("Issue date: ").append(issue).append("\n");
                if (reg != null) sb.append("Registration entity: ").append(reg).append("\n");
                if (conf != null) sb.append("Confidence: ").append(conf).append("\n");
                Object visionJson = em.get("visionJson");
                if (visionJson != null && !visionJson.toString().isBlank()) {
                    sb.append("\n--- Vision model raw analysis output ---\n");
                    String vj = visionJson.toString();
                    if (vj.length() > 3000) {
                        vj = vj.substring(0, 2997) + "...";
                    }
                    sb.append(vj).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractValidCurpClave(List<Map<String, Object>> documentResults) {
        if (documentResults == null) return null;
        for (Map<String, Object> doc : documentResults) {
            if (!Boolean.TRUE.equals(doc.get("valid"))) continue;
            Object extracted = doc.get("extracted");
            if (extracted instanceof Map<?, ?> m) {
                Object type = m.get("type");
                Object clave = m.get("curpClave");
                if (clave != null && (type == null || "CURP".equalsIgnoreCase(type.toString()))) {
                    return clave.toString().trim().toUpperCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private List<String> extractValidCurpPaths(List<Map<String, Object>> documentResults) {
        List<String> paths = new ArrayList<>();
        if (documentResults == null) return paths;
        for (Map<String, Object> doc : documentResults) {
            if (!Boolean.TRUE.equals(doc.get("valid"))) continue;
            Object extracted = doc.get("extracted");
            boolean isCurp = false;
            if (extracted instanceof Map<?, ?> m) {
                Object type = m.get("type");
                isCurp = type != null && "CURP".equalsIgnoreCase(type.toString());
            }
            Object path = doc.get("path");
            if (isCurp && path != null && !path.toString().isBlank()) {
                paths.add(path.toString());
            }
        }
        return paths;
    }

    /**
     * Upload a file as a Fineract loan document (multipart).
     * POST /loans/{loanId}/documents
     * <p>
     * Expected form fields (Mifos UI / WebKit boundary):
     * <ul>
     *   <li>{@code name} – original filename (e.g. curp_VMRR.pdf)</li>
     *   <li>{@code file} – binary PDF</li>
     *   <li>{@code description} – CURP clave (e.g. RORV810322HDFMDC02)</li>
     *   <li>{@code dateFormat} – {@code yyyy-MM-dd}</li>
     *   <li>{@code locale} – {@code es}</li>
     *   <li>{@code issuanceDate} – CURP issue date from vision (yyyy-MM-dd)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> uploadLoanDocument(Long loanId, String filePath,
                                                 String name, String description,
                                                 String issuanceDate) {
        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Document file not found: " + filePath);
        }
        String filename = path.getFileName().toString();
        String contentType = guessContentType(filename);
        String formName = (name != null && !name.isBlank()) ? name : filename;
        String formDescription = description != null ? description : "";

        log.info("Uploading loan document loanId={} file={} name={} description={} dateFormat=yyyy-MM-dd locale=es issuanceDate={}",
                loanId, filename, formName, formDescription, issuanceDate);

        org.springframework.http.client.MultipartBodyBuilder mb =
                new org.springframework.http.client.MultipartBodyBuilder();
        mb.part("name", formName);
        mb.part("file", new org.springframework.core.io.FileSystemResource(path.toFile()))
                .filename(filename)
                .contentType(MediaType.parseMediaType(contentType));
        mb.part("description", formDescription);
        mb.part("dateFormat", "yyyy-MM-dd");
        mb.part("locale", "es");
        if (issuanceDate != null && !issuanceDate.isBlank()) {
            mb.part("issuanceDate", issuanceDate.trim());
        }

        Map<String, Object> resp = webClient.post()
                .uri("/loans/{loanId}/documents", loanId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(mb.build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        Map<String, Object> out = new HashMap<>();
        out.put("path", filePath);
        out.put("name", formName);
        out.put("description", formDescription);
        out.put("dateFormat", "yyyy-MM-dd");
        out.put("locale", "es");
        if (issuanceDate != null) {
            out.put("issuanceDate", issuanceDate);
        }
        out.put("response", resp);
        if (resp != null && resp.get("resourceId") != null) {
            out.put("documentId", resp.get("resourceId"));
        }
        log.info("Loan document uploaded loanId={} response={}", loanId, resp);
        return out;
    }

    /** Backwards-compatible overload without issuanceDate. */
    public Map<String, Object> uploadLoanDocument(Long loanId, String filePath, String name, String description) {
        return uploadLoanDocument(loanId, filePath, name, description, null);
    }

    private static String guessContentType(String filename) {
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".pdf")) return "application/pdf";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
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

    public int getDefaultProductId() { return defaultProductId; }
    
    /** Issue date (yyyy-MM-dd) from vision model on a valid CURP document. */
    private String extractValidCurpIssueDate(List<Map<String, Object>> documentResults) {
        if (documentResults == null) return null;
        for (Map<String, Object> doc : documentResults) {
            Object top = doc.get("issueDate");
            if (top != null && !top.toString().isBlank()) {
                return top.toString().trim();
            }
            Object extracted = doc.get("extracted");
            if (extracted instanceof Map<?, ?> m) {
                Object issue = m.get("issueDate");
                if (issue != null && !issue.toString().isBlank()) {
                    return issue.toString().trim();
                }
            }
        }
        return null;
    }
}