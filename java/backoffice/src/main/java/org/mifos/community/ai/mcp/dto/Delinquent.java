package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Delinquent {
    Double availableDisbursementAmount;
    Integer pastDueDays;
    Integer delinquentDays;
    Double delinquentAmount;
    Double lastPaymentAmount;
    Double lastRepaymentAmount;
    Double delinquentPrincipal;
    Double delinquentInterest;
    Double delinquentFee;
    Double delinquentPenalty;
}
