package org.mifos.community.ai.mcp.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class LoanProduct {
    Integer accountingRule = 1;
    String accountMovesOutOfNPAOnlyOnArrearsCompletion = "false";
    String allowApprovedDisbursedAmountsOverApplied = "false";

    AllowAttributeOverrides allowAttributeOverrides;

    String allowVariableInstallments = "false";
    Integer amortizationType = 1;
    String canDefineInstallmentAmount = "false";
    String canUseForTopup = "false";
    List<Charge> charges;
    @NotNull
    String currencyCode;
    String dateFormat = "dd MMMM yyyy";
    Integer daysInMonthType = 1;
    Integer daysInYearType = 1;
    Integer delinquencyBucketId;
    Integer digitsAfterDecimal = 2;
    String disallowExpectedDisbursements = "false";
    Integer dueDaysForRepaymentEvent = 1;
    String enableDownPayment = "false";
    String enableInstallmentLevelDelinquency = "false";
    String externalId = "";
    Integer fixedLength;
    String holdGuaranteeFunds = "false";
    String includeInBorrowerCycle = "false";
    Integer inMultiplesOf = 0;
    Integer interestCalculationPeriodType = 1;
    @NotNull
    Integer interestRateFrequencyType = 2;
    @NotNull
    double interestRatePerPeriod;
    List<Options> interestRateVariationsForBorrowerCycle;
    String interestRecognitionOnDisbursementDate = "false";
    Integer interestType = 0;
    String isEqualAmortization = "false";
    String isInterestRecalculationEnabled = "false";
    String isLinkedToFloatingInterestRates = "false";
    String loanScheduleType = "CUMULATIVE";
    String locale = "en";
    @NotNull
    String name;
    @NotNull
    Integer numberOfRepayments;
    List<Options> numberOfRepaymentVariationsForBorrowerCycle;
    Integer overDueDaysForRepaymentEvent = 1;
    @NotNull
    double principal;
    List<Options> principalVariationsForBorrowerCycle;
    @NotNull
    Integer repaymentEvery;
    @NotNull
    Integer repaymentFrequencyType;
    Integer repaymentStartDateType = 1;
    @NotNull
    String shortName;
    String transactionProcessingStrategyCode = "creocore-strategy";
    String useBorrowerCycle = "false";
}
