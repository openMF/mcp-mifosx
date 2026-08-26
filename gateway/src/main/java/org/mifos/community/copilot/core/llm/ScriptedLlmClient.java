/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.llm;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword-driven stand-in for a real model ({@code copilot.llm.provider=mock}).
 *
 * <p>Purpose: exercise the ENTIRE pipeline (SSE contract, tool execution against a real
 * Fineract with the officer's real credential, approval pause/resume, audit) with zero LLM
 * key. Only the language understanding is faked; everything downstream is production code.
 * Also used by the agent-loop unit tests.
 */
public final class ScriptedLlmClient implements LlmClient {

    private static final Pattern CLIENT_ID = Pattern.compile("client\\s+#?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOAN_ID = Pattern.compile("loan\\s+#?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEARCH = Pattern.compile("(?:search|find|show)\\s+client\\s+([a-z]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public LlmResult complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
            Consumer<String> onToken, Consumer<String> onReasoning, BooleanSupplier cancelled) {
        Map<String, Object> last = messages.get(messages.size() - 1);
        String role = String.valueOf(last.get("role"));

        // After a tool result, summarize it (a real model would write prose here).
        if ("tool".equals(role)) {
            String summary = "Here is what Mifos X returned (mock-model mode, a real model would summarize this):\n\n```json\n"
                    + truncate(prettyJson(String.valueOf(last.get("content"))), 1200) + "\n```";
            emit(summary, onToken);
            return new LlmResult(summary, List.of());
        }

        String message = String.valueOf(last.get("content")).toLowerCase();

        Matcher approve = LOAN_ID.matcher(message);
        if (message.contains("approve") && approve.find()) {
            return new LlmResult("", List.of(new LlmToolCall("call-1", "mifos_loan_approve",
                    Map.of("loanId", Long.parseLong(approve.group(1)), "approvedOnDate", "today"))));
        }
        Matcher loan = LOAN_ID.matcher(message);
        if (message.contains("loan") && loan.find()) {
            return new LlmResult("", List.of(new LlmToolCall("call-1", "mifos_loan_details",
                    Map.of("loanId", Long.parseLong(loan.group(1))))));
        }
        Matcher client = CLIENT_ID.matcher(message);
        if (client.find()) {
            return new LlmResult("", List.of(new LlmToolCall("call-1", "mifos_client_details",
                    Map.of("clientId", Long.parseLong(client.group(1))))));
        }
        Matcher search = SEARCH.matcher(message);
        if (search.find()) {
            return new LlmResult("", List.of(new LlmToolCall("call-1", "mifos_client_search",
                    Map.of("query", search.group(1)))));
        }

        String fallback = "I am running in **mock-LLM mode** (no API key configured). I can still run real "
                + "Mifos X operations with your own login. Try: \"show client 1\", \"show loan 1\", or "
                + "\"approve loan 1\".";
        emit(fallback, onToken);
        return new LlmResult(fallback, List.of());
    }

    private void emit(String text, Consumer<String> onToken) {
        for (String word : text.split("(?<=\\s)")) {
            onToken.accept(word);
            try {
                Thread.sleep(12);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "\n… (truncated)" : value;
    }

    /** Pretty-print when the tool result is JSON; pass through anything else. */
    private String prettyJson(String value) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(value));
        } catch (Exception e) {
            return value;
        }
    }
}
