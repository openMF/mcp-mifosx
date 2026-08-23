/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.community.copilot.core.auth.CallContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enrichment over real HTTP against a stand-in core banking API.
 *
 * <p>This is the step that turns "loanId 12" into "Weekly Loan for Aisha Bello, USD 28,000.00"
 * on the card an officer signs off. It runs with the officer's own credential like every other
 * call, and it is presentation only: a lookup that fails must leave the card thinner, never
 * break the turn.
 */
class EnrichmentTest {

    private static final String LOAN_JSON = """
            {
              "id": 12,
              "accountNo": "000000012",
              "clientName": "Aisha Bello",
              "loanProductName": "Weekly Loan",
              "principal": 30000,
              "currency": { "code": "NGN", "displaySymbol": "NGN" },
              "summary": { "principalOutstanding": 12500.5 }
            }
            """;

    private static final String PRODUCT_JSON = """
            { "id": 3, "name": "Agriculture Term Loan", "currency": { "code": "KES" } }
            """;

    private static final String CLIENT_JSON = """
            { "id": 7, "displayName": "Grace Wanjiru", "accountNo": "000000007", "officeName": "Nairobi Branch" }
            """;

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestedPaths = new ArrayList<>();
    private final Map<String, Integer> statusOverrides = new LinkedHashMap<>();
    /** Body served for an overridden status, so a 403 can carry a reason or carry none. */
    private String errorBody;

    private final CallContext officer = new CallContext("Basic abc", "default", "corr-1");

    @BeforeEach
    void startStubFineract() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStubFineract() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestedPaths.add(path);
        Integer override = statusOverrides.get(path);
        String body = path.contains("loanproducts") ? PRODUCT_JSON : path.contains("clients") ? CLIENT_JSON : LOAN_JSON;
        int status = override != null ? override : 200;
        if (status != 200) {
            body = errorBody != null ? errorBody : "{\"developerMessage\":\"nope\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (InputStream ignored = exchange.getRequestBody()) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private ToolDefinition loanApprove(ToolDefinition.Enrich... blocks) {
        return new ToolDefinition("mifos_loan_approve", "Approve a loan application.", true,
                "Approve {productName} for {clientName}",
                List.of(new ToolDefinition.Param("loanId", "integer", true, null, "Loan account", null, false)),
                new ToolDefinition.RestMapping("POST", "/loans/{loanId}", "{}"),
                List.of(),
                List.of(blocks),
                null);
    }

    private Map<String, String> enrichLoan12(ToolDefinition.Enrich... blocks) {
        return new FineractRestToolExecutor(baseUrl)
                .enrich(loanApprove(blocks), Map.of("loanId", 12), officer);
    }

    @Test
    void theCardLearnsWhoTheClientIsAndWhichProductTheyHold() {
        Map<String, String> rows = enrichLoan12(
                new ToolDefinition.Enrich("/loans/{loanId}", "currency.code", orderedFields()));

        assertThat(rows).containsEntry("Client", "Aisha Bello")
                .containsEntry("Loan account", "000000012")
                .containsEntry("Product", "Weekly Loan");
        // The identifier the model passed in never becomes a row.
        assertThat(rows).doesNotContainKey("loanId");
    }

    @Test
    void theRowsKeepTheOrderTheManifestDeclares() {
        // Who and which account first, then the figures: the order is a review sequence,
        // not an accident of map iteration.
        Map<String, String> rows = enrichLoan12(
                new ToolDefinition.Enrich("/loans/{loanId}", "currency.code", orderedFields()));

        assertThat(rows.keySet().stream().filter((k) -> !Display.isReserved(k)))
                .containsExactly("Client", "Loan account", "Product", "Applied for");
    }

    private static Map<String, String> orderedFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Client", "clientName");
        fields.put("Loan account", "accountNo");
        fields.put("Product", "loanProductName");
        fields.put("Applied for", "#money:principal");
        return fields;
    }

    @Test
    void amountsAreWrittenWithTheCurrencyTheAccountIsHeldIn() {
        Map<String, String> rows = enrichLoan12(
                new ToolDefinition.Enrich("/loans/{loanId}", "currency.code", orderedFields()));

        assertThat(rows).containsEntry("Applied for", "NGN 30,000.00");
    }

    @Test
    void aDottedPathReachesIntoNestedFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Outstanding", "#money:summary.principalOutstanding");

        Map<String, String> rows = enrichLoan12(new ToolDefinition.Enrich("/loans/{loanId}", "currency.code", fields));

        assertThat(rows).containsEntry("Outstanding", "NGN 12,500.50");
    }

    @Test
    void theCurrencyIsCarriedForTheCardWithoutBecomingARowTheOfficerReads() {
        Map<String, String> rows = enrichLoan12(
                new ToolDefinition.Enrich("/loans/{loanId}", "currency.code", orderedFields()));

        assertThat(rows).containsEntry(Display.CURRENCY, "NGN");
        assertThat(Display.isReserved(Display.CURRENCY)).isTrue();
    }

    @Test
    void severalLookupsCombineSoOneCardCanNameBothTheClientAndTheProduct() {
        // A new loan application: the client lives behind one endpoint, the product behind
        // another, and the officer needs to see both before approving.
        Map<String, String> clientFields = new LinkedHashMap<>();
        clientFields.put("Client", "displayName");
        clientFields.put("Client account", "accountNo");
        Map<String, String> productFields = new LinkedHashMap<>();
        productFields.put("Product", "name");

        Map<String, String> rows = new FineractRestToolExecutor(baseUrl).enrich(
                loanApprove(new ToolDefinition.Enrich("/clients/{clientId}", null, clientFields),
                        new ToolDefinition.Enrich("/loanproducts/{productId}", "currency.code", productFields)),
                Map.of("clientId", 7, "productId", 3), officer);

        assertThat(rows).containsEntry("Client", "Grace Wanjiru")
                .containsEntry("Client account", "000000007")
                .containsEntry("Product", "Agriculture Term Loan")
                .containsEntry(Display.CURRENCY, "KES");
        assertThat(enrichmentCalls()).containsExactly("/clients/7", "/loanproducts/3");
    }

    /** Paths hit for the card itself, excluding the tenant's business-date lookup. */
    private List<String> enrichmentCalls() {
        return requestedPaths.stream().filter((path) -> !path.contains("businessdate")).toList();
    }

    @Test
    void aLookupThatFailsLeavesTheCardThinnerRatherThanBreakingTheTurn() {
        statusOverrides.put("/loans/12", 404);

        Map<String, String> rows = enrichLoan12(
                new ToolDefinition.Enrich("/loans/{loanId}", "currency.code", orderedFields()));

        assertThat(rows).isEmpty();
    }

    @Test
    void oneFailedLookupDoesNotDiscardWhatTheOthersFound() {
        Map<String, String> clientFields = new LinkedHashMap<>();
        clientFields.put("Client", "displayName");
        Map<String, String> productFields = new LinkedHashMap<>();
        productFields.put("Product", "name");
        statusOverrides.put("/loanproducts/3", 500);

        Map<String, String> rows = new FineractRestToolExecutor(baseUrl).enrich(
                loanApprove(new ToolDefinition.Enrich("/clients/{clientId}", null, clientFields),
                        new ToolDefinition.Enrich("/loanproducts/{productId}", "currency.code", productFields)),
                Map.of("clientId", 7, "productId", 3), officer);

        assertThat(rows).containsEntry("Client", "Grace Wanjiru");
        assertThat(rows).doesNotContainKey("Product");
    }

    @Test
    void aToolWithNoEnrichmentMakesNoCallsAtAll() {
        Map<String, String> rows = new FineractRestToolExecutor(baseUrl)
                .enrich(loanApprove(), Map.of("loanId", 12), officer);

        assertThat(rows).isEmpty();
        assertThat(enrichmentCalls()).isEmpty();
    }

    /**
     * Fineract answers 403 for two unrelated things, and conflating them sends an officer to
     * an administrator when the real problem is a field they could fix themselves.
     */
    @Test
    void aRuleViolationIsReportedAsARejectionWithItsReasonNotAsADeniedRole() throws Exception {
        // Verbatim from sandbox.mifos.community, approving a loan dated after its expected
        // disbursement: HTTP 403, but the officer's role is not the problem.
        statusOverrides.put("/loans/12", 403);
        errorBody = "{\"developerMessage\":\"Request was understood but caused a domain rule violation.\","
                + "\"httpStatusCode\":\"403\",\"userMessageGlobalisationCode\":"
                + "\"validation.msg.domain.rule.violation\",\"errors\":[{\"defaultUserMessage\":"
                + "\"The expected disbursement date 2026-08-20 should be either on or after the approval"
                + " date: 2026-08-22\"}]}";

        String result = new FineractRestToolExecutor(baseUrl)
                .execute(loanApprove(), Map.of("loanId", 12), officer, "cop-1");

        assertThat(result).contains("expected disbursement date");
        assertThat(result).contains("\"httpStatus\":403");
    }

    @Test
    void a403WithNothingToExplainIsStillTreatedAsADeniedRole() {
        // A genuine RBAC refusal carries no errors[] to pass on, so there is nothing more
        // useful to tell the officer than that they were denied.
        statusOverrides.put("/loans/12", 403);
        errorBody = "{\"developerMessage\":\"Not authorised\"}";

        assertThatThrownBy(() -> new FineractRestToolExecutor(baseUrl)
                .execute(loanApprove(), Map.of("loanId", 12), officer, "cop-1"))
                .isInstanceOf(ToolExecutionException.class)
                .matches((e) -> ((ToolExecutionException) e).isPermissionFailure());
    }

    @Test
    void anExpiredSessionIsAlwaysAnAuthFailure() {
        statusOverrides.put("/loans/12", 401);

        assertThatThrownBy(() -> new FineractRestToolExecutor(baseUrl)
                .execute(loanApprove(), Map.of("loanId", 12), officer, "cop-1"))
                .isInstanceOf(ToolExecutionException.class)
                .matches((e) -> ((ToolExecutionException) e).isAuthFailure());
    }
}
