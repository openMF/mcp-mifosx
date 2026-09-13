/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.agent;

import org.mifos.community.copilot.core.contract.StreamEvent;

/** Where the agent loop emits wire-contract events; the Spring shell bridges this to SSE. */
public interface EventSink {

    void emit(StreamEvent event);

    /** True when the browser cancelled (stop button / closed socket); the loop stops quietly. */
    boolean isCancelled();
}
