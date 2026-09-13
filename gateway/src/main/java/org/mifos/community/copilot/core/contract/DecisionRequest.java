/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.contract;

/** Body of {@code POST /copilot/api/v1/actions/{cardId}/decision}: approve or reject. */
public record DecisionRequest(String decision, String clientMsgId) {

    public boolean isApprove() {
        return "approve".equalsIgnoreCase(decision);
    }
}
