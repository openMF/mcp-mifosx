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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class ChargeAndSavingsValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void createChargeSetsChargePaymentModeForLoanAndOmitsUnusedMonthDayFormat() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(MAPPER.createObjectNode(), captured);

        server.createCharge("Late fee", "15", "USD", 1, 1, 1, "false", "true", null);
        JsonNode loanCharge = MAPPER.readTree(captured.get());
        assertEquals(0, loanCharge.get("chargePaymentMode").asInt());
        assertFalse(loanCharge.has("monthDayFormat"));

        server.createCharge("Withdrawal fee", "2", "USD", 2, 5, 1, "false", "true", null);
        JsonNode savingsCharge = MAPPER.readTree(captured.get());
        assertFalse(savingsCharge.has("chargePaymentMode"));
        assertFalse(savingsCharge.has("monthDayFormat"));

        assertThrows(
                ToolCallException.class,
                () -> server.createCharge("Bad mode", "10", "USD", 1, 1, 1, "false", "true", 2));
    }

    @Test
    void newSavingsTransactionRejectsUnknownPaymentTypeWithToolCallExceptionWithoutPosting() {
        ObjectNode template = MAPPER.createObjectNode();
        template.put("date", "10 June 2025");
        ArrayNode options = template.putArray("paymentTypeOptions");
        options.addObject().put("id", 4).put("name", "Cash");

        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(template, captured);

        ToolCallException ex = assertThrows(
                ToolCallException.class,
                () -> server.newSavingsTransaction(8, "deposit", null, "Bitcoin", 50.0, null));
        assertTrue(ex.getMessage().contains("Cash"));
        assertEquals(null, captured.get());
    }

    @Test
    void newSavingsTransactionFormatsDatesInEnglishEvenUnderNonEnglishJvmLocale() throws Exception {
        ObjectNode template = MAPPER.createObjectNode();
        ArrayNode dateArr = template.putArray("date");
        dateArr.add(2025);
        dateArr.add(6);
        dateArr.add(10);
        ArrayNode options = template.putArray("paymentTypeOptions");
        options.addObject().put("id", 4).put("name", "Money Transfer");

        AtomicReference<String> captured = new AtomicReference<>();
        MifosXServer server = serverWithClient(template, captured);

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.FRENCH);
        try {
            server.newSavingsTransaction(8, "deposit", null, "money transfer", 50.0, null);
            JsonNode body = MAPPER.readTree(captured.get());
            assertEquals(4, body.get("paymentTypeId").asInt());
            assertEquals("10 June 2025", body.get("transactionDate").asText());

            ObjectNode noDateTemplate = MAPPER.createObjectNode();
            noDateTemplate.putArray("paymentTypeOptions").addObject().put("id", 4).put("name", "Cash");
            MifosXServer fallbackServer = serverWithClient(noDateTemplate, captured);
            fallbackServer.newSavingsTransaction(8, "deposit", null, "Cash", 25.0, null);
            JsonNode fallbackBody = MAPPER.readTree(captured.get());
            String expectedTodayEn = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
            assertEquals(expectedTodayEn, fallbackBody.get("transactionDate").asText());
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static MifosXServer serverWithClient(JsonNode templateResponse, AtomicReference<String> capturedPayload) {
        MifosXServer server = new MifosXServer();
        server.mifosXClient = (MifosXClient) Proxy.newProxyInstance(
                MifosXClient.class.getClassLoader(),
                new Class<?>[] { MifosXClient.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSavingsTransactionTemplate":
                            return templateResponse;
                        case "createCharge":
                            capturedPayload.set((String) args[0]);
                            return MAPPER.createObjectNode().put("resourceId", 1);
                        case "newSavingsTransaction":
                            capturedPayload.set((String) args[2]);
                            return MAPPER.createObjectNode().put("resourceId", 2);
                        default:
                            return MAPPER.createObjectNode();
                    }
                });
        return server;
    }
}
