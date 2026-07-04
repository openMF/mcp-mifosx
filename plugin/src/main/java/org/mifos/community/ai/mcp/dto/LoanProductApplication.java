package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Setter
@Getter
public class LoanProductApplication {
    Integer productId;
    Integer loanOfficerId;
    Integer loanPurposeId;
    Integer fundId;
    String submittedOnDate;
    String expectedDisbursementDate;
    String externalId;
    Integer linkAccountId;
    String createStandingInstructionAtDisbursement;
    Integer loanTermFrequency;
    Integer loanTermFrequencyType;
    Integer numberOfRepayments;
    Integer repaymentEvery;
    Integer repaymentFrequencyType;
    Integer repaymentFrequencyNthDayType;
    Integer repaymentFrequencyDayOfWeekType;
    String repaymentsStartingFromDate;
    String interestChargedFromDate;
    Integer interestType;
    String isEqualAmortization;
    Integer amortizationType;
    Integer interestCalculationPeriodType;
    Integer loanIdToClose;
    String isTopup;
    String transactionProcessingStrategyCode;
    Integer interestRateFrequencyType;
    Double interestRatePerPeriod;
    List<Charge> charges;
    List<AddLoanProductCollateralRequest> collateral;
    String dateFormat;
    String locale;
    Integer clientId;
    String loanType;
    Double principal;
    String allowPartialPeriodInterestCalcualtion;
}
