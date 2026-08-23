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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where the values in a request body come from.
 *
 * <p>Two of them were literals written into the manifest, and both were wrong in a way that
 * only shows up outside a one-office test system. Every client was created in office 1, and
 * every loan carried a twelve percent rate on a monthly schedule whatever its product said.
 * A value that belongs to the officer's session or to the product has to be read from there.
 */
class ValueSourceTest {

    private static final String PRODUCT_JSON = """
            {
              "id": 3,
              "name": "Weekly Market Loan",
              "interestRatePerPeriod": 2.5,
              "numberOfRepayments": 16,
              "repaymentEvery": 1,
              "repaymentFrequencyType": { "id": 1, "value": "Weeks" },
              "amortizationType": { "id": 1, "value": "Equal installments" },
              "interestType": { "id": 0, "value": "Declining Balance" },
              "interestCalculationPeriodType": { "id": 1, "value": "Same as repayment period" },
              "transactionProcessingStrategyCode": "early-repayment-strategy"
            }
            """;

    private static final String LOAN_BODY = "{\"clientId\":\"${clientId}\",\"productId\":\"${productId}\","
            + "\"principal\":\"${principal}\",\"repaymentFrequencyType\":\"${repaymentFrequencyType}\","
            + "\"interestRatePerPeriod\":\"${interestRatePerPeriod}\","
            + "\"transactionProcessingStrategyCode\":\"${transactionProcessingStrategyCode}\","
            + "\"loanType\":\"individual\"}";

    private HttpServer server;
    private String baseUrl;
    private final List<String> requested = new ArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requested.add(exchange.getRequestURI().getPath());
        byte[] bytes = PRODUCT_JSON.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ToolDefinition loanCreate() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("interestRatePerPeriod", "interestRatePerPeriod");
        fields.put("repaymentFrequencyType", "repaymentFrequencyType.id");
        fields.put("transactionProcessingStrategyCode", "transactionProcessingStrategyCode");
        return new ToolDefinition("mifos_loan_create", "Submit a loan application.", true, "New loan",
                List.of(new ToolDefinition.Param("productId", "integer", true, null, "Product", null, false)),
                new ToolDefinition.RestMapping("POST", "/loans", LOAN_BODY),
                List.of(), List.of(),
                new ToolDefinition.Defaults("/loanproducts/{productId}", fields),
                Map.of());
    }

    @Test
    void aLoanTakesItsTermsFromItsProductRatherThanFromTheManifest() {
        // The product is weekly at 2.5 percent on an early-repayment strategy. None of that
        // was reachable while the manifest sent 12, monthly and mifos-standard-strategy.
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("clientId", 7);
        args.put("productId", 3);
        args.put("principal", 5000);

        String body = executor.buildBody(LOAN_BODY, executor.withDefaults(loanCreate(), args, officer()),
                "2026-08-22", Map.of());

        assertThat(body).contains("\"interestRatePerPeriod\":2.5");
        assertThat(body).contains("\"repaymentFrequencyType\":1");
        assertThat(body).contains("\"transactionProcessingStrategyCode\":\"early-repayment-strategy\"");
    }

    @Test
    void anAmountTheOfficerNamedIsNotOverwrittenByTheProduct() {
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("productId", 3);
        args.put("interestRatePerPeriod", 9);

        Map<String, Object> resolved = executor.withDefaults(loanCreate(), args, officer());

        assertThat(resolved).containsEntry("interestRatePerPeriod", 9);
    }

    @Test
    void nothingIsLookedUpWhenTheOfficerAlreadyNamedEverything() {
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("productId", 3);
        args.put("interestRatePerPeriod", 9);
        args.put("repaymentFrequencyType", 1);
        args.put("transactionProcessingStrategyCode", "mifos-standard-strategy");

        executor.withDefaults(loanCreate(), args, officer());

        assertThat(requested).noneMatch((path) -> path.contains("loanproducts"));
    }

    @Test
    void theOfficeComesFromTheSessionAndNotFromTheModel() {
        // This is the one that was wrong everywhere except a single-office test system.
        String template = "{\"officeId\":\"${session.officeId}\",\"firstname\":\"${firstname}\"}";

        String body = new FineractRestToolExecutor(baseUrl)
                .buildBody(template, Map.of("firstname", "Aisha"), "2026-08-22", Map.of("officeId", 4L));

        assertThat(body).contains("\"officeId\":4");
    }

    @Test
    void aModelCannotSupplyTheOfficeByGuessingTheParameterName() {
        // An argument called officeId is not a session fact and must not become one.
        String template = "{\"officeId\":\"${session.officeId}\",\"firstname\":\"${firstname}\"}";

        String body = new FineractRestToolExecutor(baseUrl).buildBody(template,
                Map.of("firstname", "Aisha", "officeId", 99), "2026-08-22", Map.of("officeId", 4L));

        assertThat(body).contains("\"officeId\":4");
        assertThat(body).doesNotContain("99");
    }

    @Test
    void anAbsentSessionFactRefusesTheRequestRatherThanQuietlyDroppingTheField() {
        // Dropping it would post a client with no office and let Fineract decide, which is the
        // same class of wrongness the session token was introduced to remove.
        String template = "{\"officeId\":\"${session.officeId}\",\"firstname\":\"${firstname}\"}";
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);

        assertThatThrownBy(() -> executor.buildBody(template, Map.of("firstname", "Aisha"), "2026-08-22", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("office");
    }

    private CallContext officer() {
        return new CallContext("Basic abc", "default", "corr-1", Map.of("officeId", 4L));
    }
}
