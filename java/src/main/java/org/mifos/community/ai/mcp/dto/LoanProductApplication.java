package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Setter
@Getter
public class LoanProductApplication {
    String allowPartialPeriodInterestCalcualtion;
    Integer amortizationType;
    List<Object> charges;
    Integer clientId;
    List<Object> collateral;
    String createStandingInstructionAtDisbursement;
    String dateFormat;
    String expectedDisbursementDate;
    Integer externalId;
    Integer fundId;
    Integer interestCalculationPeriodType;
    String interestChargedFromDate;
    Integer interestRateFrequencyType;
    BigDecimal interestRatePerPeriod;
    Integer interestType;
    String isEqualAmortization;
    String isTopup;
    String linkAccountId;
    String loanIdToClose;
    String loanOfficerId;
    String loanPurposeId;
    Integer loanTermFrequency;
    Integer loanTermFrequencyType;
    String loanType;
    String locale;
    Integer numberOfRepayments;
    BigDecimal principal;
    Integer productId;
    Integer repaymentEvery;
    String repaymentFrequencyDayOfWeekType;
    String repaymentFrequencyNthDayType;
    Integer repaymentFrequencyType;
    String repaymentsStartingFromDate;
    String submittedOnDate;
    String transactionProcessingStrategyCode;
}
