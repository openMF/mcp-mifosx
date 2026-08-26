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

/**
 * Provider-agnostic LLM interface (ADR-001 §2.2): Groq cloud today, Ollama fully on-prem, any
 * OpenAI-compatible engine tomorrow, so swapping providers is configuration and never code.
 */
public interface LlmClient {

    /**
     * Run one completion turn, streaming text deltas to {@code onToken} as they arrive.
     *
     * @param messages  OpenAI-style chat messages (role/content/tool_calls/tool_call_id maps)
     * @param tools     OpenAI-style tool schemas the model may call (already role-filtered)
     * @param onToken   receives assistant text deltas for live streaming to the browser
     * @param onReasoning receives the model's reasoning deltas, where it produces any, kept
     *                  apart from the answer so the two are never shown as the same thing
     * @param cancelled polled between chunks; when true the turn is abandoned quietly
     * @return the assembled result: final text plus any tool calls the model requested
     * @throws LlmException when the provider is unreachable, times out, or rejects the request
     */
    LlmResult complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> onToken,
            Consumer<String> onReasoning, BooleanSupplier cancelled) throws LlmException;

    /** For callers with no use for the reasoning, such as tests. */
    default LlmResult complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
            Consumer<String> onToken, BooleanSupplier cancelled) throws LlmException {
        return complete(messages, tools, onToken, (ignored) -> {
        }, cancelled);
    }
}
