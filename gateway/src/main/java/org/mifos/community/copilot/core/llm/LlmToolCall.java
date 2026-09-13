/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.llm;

import java.util.Map;

/** One function call requested by the model (OpenAI tool-calls shape, already parsed). */
public record LlmToolCall(String id, String name, Map<String, Object> arguments) {}
