/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mifos.community.ai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.mcp.server.ToolCallException;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mifos.community.ai.mcp.client.MifosXClient;

class UserRecommendationValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void savingsTransactionRejectsUnknownPaymentType() {
        ObjectNode template = savingsTemplate("Cash");
        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(template, captured);

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> server.newSavingsTransaction(8, "deposit", null, "Bitcoin", 50.0, null));
        assertTrue(ex.getMessage().contains("Cash"));
        assertNull(captured.get());
    }

    @Test
    void savingsTransactionRejectsMissingPaymentTypeOptions() {
        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(MAPPER.createObjectNode(), captured);

        assertThrows(ToolCallException.class,
                () -> server.newSavingsTransaction(8, "deposit", null, "Cash", 50.0, null));
        assertNull(captured.get());
    }

    @Test
    void savingsTransactionFormatsDateInEnglishUnderFrenchLocale() throws Exception {
        ObjectNode template = savingsTemplateWithDate(2025, 6, 10, "Money Transfer");
        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(template, captured);

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.FRENCH);
        try {
            server.newSavingsTransaction(8, "deposit", null, "money transfer", 50.0, null);
            JsonNode body = MAPPER.readTree(captured.get());
            assertEquals(4, body.get("paymentTypeId").asInt());
            assertEquals("10 June 2025", body.get("transactionDate").asText());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void savingsTransactionFallsBackToTodayWhenNoDateInTemplate() throws Exception {
        ObjectNode noDateTemplate = MAPPER.createObjectNode();
        noDateTemplate.putArray("paymentTypeOptions").addObject().put("id", 4).put("name", "Cash");
        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(noDateTemplate, captured);

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.FRENCH);
        try {
            server.newSavingsTransaction(8, "deposit", null, "Cash", 25.0, null);
            JsonNode body = MAPPER.readTree(captured.get());
            String expected = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
            assertEquals(expected, body.get("transactionDate").asText());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void disburseRejectsUnknownPaymentType() {
        ObjectNode template = loanTemplate("Money Transfer");
        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(template, captured);

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> server.disburseLoanAccount(3, null, null, "Wire", null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Money Transfer"));
        assertNull(captured.get());
    }

    @Test
    void repaymentRejectsUnknownPaymentType() {
        ObjectNode template = loanTemplate("Money Transfer");
        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(template, captured);

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> server.makeLoanRepayment(3, "Wire", 100.0, null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("Money Transfer"));
        assertNull(captured.get());
    }

    @Test
    void disburseAndRepaymentRejectMissingPaymentTypeOptions() {
        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(MAPPER.createObjectNode(), captured);

        assertThrows(ToolCallException.class,
                () -> server.disburseLoanAccount(3, null, null, "Money Transfer", null, null, null, null, null, null));
        assertThrows(ToolCallException.class,
                () -> server.makeLoanRepayment(3, "Money Transfer", 100.0, null, null, null, null, null, null, null, null));
        assertNull(captured.get());
    }

    @Test
    void addFamilyMemberAcceptsOmittedIsDependentWithoutNpe() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(MAPPER.createObjectNode(), captured);

        server.addFamilyMember(1, "Jane", null, "Doe", null, 30, null, "Sister", "Female", null, null, "03 June 1995", null, null);
        JsonNode body = MAPPER.readTree(captured.get());
        assertEquals("false", body.get("isDependent").asText());
    }

    // ── Template helpers ─────────────────────────────────────────────────

    private static ObjectNode savingsTemplate(String paymentTypeName) {
        ObjectNode tpl = MAPPER.createObjectNode();
        tpl.put("date", "10 June 2025");
        tpl.putArray("paymentTypeOptions").addObject().put("id", 4).put("name", paymentTypeName);
        return tpl;
    }

    private static ObjectNode savingsTemplateWithDate(int y, int m, int d, String paymentTypeName) {
        ObjectNode tpl = MAPPER.createObjectNode();
        ArrayNode dateArr = tpl.putArray("date");
        dateArr.add(y);
        dateArr.add(m);
        dateArr.add(d);
        tpl.putArray("paymentTypeOptions").addObject().put("id", 4).put("name", paymentTypeName);
        return tpl;
    }

    private static ObjectNode loanTemplate(String paymentTypeName) {
        ObjectNode tpl = MAPPER.createObjectNode();
        tpl.putArray("paymentTypeOptions").addObject().put("id", 1).put("name", paymentTypeName);
        return tpl;
    }

    // ── Proxy-based mock client ──────────────────────────────────────────

    private static Object handleProxyCall(String methodName, Object[] args,
            JsonNode templateResponse, AtomicReference<String> capturedPayload) {
        switch (methodName) {
            case "getSavingsTransactionTemplate":
            case "getDisburseLoanAccount":
            case "getLoanRepaymentTemplate":
                return templateResponse;
            case "getCodeValues":
                return MAPPER.createArrayNode();
            case "newSavingsTransaction":
            case "disburseLoanAccount":
            case "loanRepayment":
                capturedPayload.set((String) args[2]);
                return MAPPER.createObjectNode().put("resourceId", 1);
            case "addFamilyMember":
                capturedPayload.set((String) args[1]);
                return MAPPER.createObjectNode().put("resourceId", 1);
            default:
                return MAPPER.createObjectNode();
        }
    }

    private static MifosXServer serverWithClient(JsonNode templateResponse, AtomicReference<String> capturedPayload) {
        MifosXServer server = new MifosXServer();
        server.mifosXClient = (MifosXClient) Proxy.newProxyInstance(
                MifosXClient.class.getClassLoader(),
                new Class<?>[] { MifosXClient.class },
                (proxy, method, args) -> handleProxyCall(method.getName(), args, templateResponse, capturedPayload));
        return server;
    }
}
