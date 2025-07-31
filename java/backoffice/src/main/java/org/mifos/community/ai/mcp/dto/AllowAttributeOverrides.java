package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AllowAttributeOverrides {
    String amortizationType = "true";
    String graceOnArrearsAgeing = "true";
    String graceOnPrincipalAndInterestPayment = "true";
    String inArrearsTolerance = "true";
    String interestCalculationPeriodType = "true";
    String interestType = "true";
    String repaymentEvery = "true";
    String transactionProcessingStrategyCode = "true";
}
