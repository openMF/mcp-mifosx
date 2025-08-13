package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Status {
    Integer id;
    String code;
    String value;
    String pendingApproval;
    String waitingForDisbursal;
    String active;
    String closedObligationsMet;
    String closedWrittenOff;
    String closedRescheduled;
    String closed;
    String overpaid;
}
