package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoanProductTemplate {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Integer id;
    String name;
    String shortName;
    String externalId;
    String includeInBorrowerCycle;
    String useBorrowerCycle;
    String status;
    Currency currency;
    Double principal;
    Double minPrincipal;
    Double maxPrincipal;
    Integer numberOfRepayments;
    Integer minNumberOfRepayments;
    Integer maxNumberOfRepayments;
    Integer repaymentEvery;
    Types repaymentFrequencyType;
    Double interestRatePerPeriod;
    Double minInterestRatePerPeriod;
    Double maxInterestRatePerPeriod;
    Types interestRateFrequencyType;
    Double annualInterestRate;
    String isLinkedToFloatingInterestRates;
    String isFloatingInterestRateCalculationAllowed;
    String allowVariableInstallments;
    Integer minimumGap;
    Integer maximumGap;
    Types amortizationType;
    Types interestType;
    Types interestCalculationPeriodType;
    String allowPartialPeriodInterestCalculation;
    String transactionProcessingStrategyCode;
    String transactionProcessingStrategyName;
    List<Object> paymentAllocation;
    List<Object> creditAllocation;
    Types daysInMonthType;
    Types daysInYearType;
    String isInterestRecalculationEnabled;
    String canDefineInstallmentAmount;
    Types repaymentStartDateType;
    List<Object> supportedInterestRefundTypes;
    Options chargeOffBehaviour;
    List<Charge> charges;
    List<Object> principalVariationsForBorrowerCycle;
    List<Object> interestRateVariationsForBorrowerCycle;
    List<Object> numberOfRepaymentVariationsForBorrowerCycle;
    Types accountingRule;
    String canUseForTopup;
    String enableAccrualActivityPosting;
    String isRatesEnabled;
    List<Object> rates;
    List<Types> advancedPaymentAllocationTransactionTypes;
    List<Types> advancedPaymentAllocationFutureInstallmentAllocationRules;
    List<Types> advancedPaymentAllocationTypes;
    List<Object> creditAllocationTransactionTypes;
    List<Object> creditAllocationAllocationTypes;
    String multiDisburseLoan;
    Integer maxTrancheCount;
    String disallowExpectedDisbursements;
    String allowApprovedDisbursedAmountsOverApplied;
    Integer overAppliedNumber;
    Integer principalThresholdForLastInstallment;
    String holdGuaranteeFunds;
    String accountMovesOutOfNPAOnlyOnArrearsCompletion;
    AllowAttributeOverrides allowAttributeOverrides;
    String syncExpectedWithDisbursementDate;
    String isEqualAmortization;
    List<DelinquencyBucket> delinquencyBucketOptions;
    DelinquencyBucket delinquencyBucket;
    Integer dueDaysForRepaymentEvent;
    Integer overDueDaysForRepaymentEvent;
    String enableDownPayment;
    String enableAutoRepaymentForDownPayment;
    String enableInstallmentLevelDelinquency;
    Types loanScheduleType;
    Types loanScheduleProcessingType;
    String interestRecognitionOnDisbursementDate;
    List<Options> daysInYearCustomStrategyOptions;
    String enableIncomeCapitalization;
    List<Options> capitalizedIncomeCalculationTypeOptions;
    List<Options> capitalizedIncomeStrategyOptions;
    List<Options> capitalizedIncomeTypeOptions;
}
