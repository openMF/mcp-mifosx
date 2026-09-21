/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.agentic.loan.workflow;

import org.community.mifos.agentic.loan.model.LoanApplication;
import org.community.mifos.agentic.loan.model.LoanDecision;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface SupervisorWorkflow {

    @WorkflowMethod
    LoanDecision processLoan(LoanApplication application);

    @SignalMethod
    void humanReview(String action, String comments);

    @SignalMethod
    void documentsUploaded(java.util.List<String> paths);

    @QueryMethod
    LoanDecision getSummary();

    @QueryMethod
    LoanDecision getFinalResult();

    @QueryMethod
    String getStatus();
}
