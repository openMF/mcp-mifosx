/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.llm;

import java.util.List;

/** Assembled outcome of one LLM turn: the streamed text and any requested tool calls. */
public record LlmResult(String text, List<LlmToolCall> toolCalls) {

    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
