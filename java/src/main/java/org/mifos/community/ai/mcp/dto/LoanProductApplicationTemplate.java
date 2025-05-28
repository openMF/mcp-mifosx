package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class LoanProductApplicationTemplate {
    Integer clientId;
    String clientAccountNo;
    String clientName;
    String clientExternalId;
    Integer clientOfficeId;
    String loanProductName;
    String isLoanProductLinkedToFloatingRate;
    Currency currency;
    Double principal;
    Double approvedPrincipal;
    Double proposedPrincipal;
    Double netDisbursalAmount;
    Integer termFrequency;
    Options termPeriodFrequencyType;
    Integer numberOfRepayments;
    Integer repaymentEvery;
    Options repaymentFrequencyType;
    Double interestRatePerPeriod;
    Options interestRateFrequencyType;
    Double annualInterestRate;
    String isFloatingInterestRate;
    Options amortizationType;
    Options interestType;
    Options interestCalculationPeriodType;
    String allowPartialPeriodInterestCalculation;
    String transactionProcessingStrategyCode;
    String disallowExpectedDisbursements;
    Timeline timeline;
    List<Charge> charges;
    List<LoanProduct> productOptions;
    LoanOfficer loanOfficerOptions;
    List<Options> loanPurposeOptions;
    List<Options> fundOptions;
    List<Options> termFrequencyTypeOptions;
    List<Options> repaymentFrequencyTypeOptions;
    List<Options> repaymentFrequencyNthDayTypeOptions;
    List<Options> repaymentFrequencyDaysOfWeekTypeOptions;
    List<Options> interestRateFrequencyTypeOptions;
    List<Options> amortizationTypeOptions;
    List<Options> interestTypeOptions;
    List<Options> interestCalculationPeriodTypeOptions;
    List<Options> transactionProcessingStrategyOptions;
    List<Options> chargeOptions;
    List<Options> loanCollateralOptions;
    List<Options> loanScheduleTypeOptions;
    List<Options> loanScheduleProcessingTypeOptions;
    List<Options> daysInYearCustomStrategyOptions;
    List<Options> accountLinkingOptions;
    String multiDisburseLoan;
    String canDefineInstallmentAmount;
    String isTopup;
    String fraud;
    LoanProduct product;

    List<Charge> overdueCharges;
    Options daysInMonthType;
    Options daysInYearType;
    String isInterestRecalculationEnabled;
    Object interestRecalculationData; // Change for interestRecalculationData DTO CLASS
    String isVariableInstallmentsAllowed;
    Integer minimumGap;
    Integer maximumGap;
    String isEqualAmortization;
    String isRatesEnabled;
    Integer productId;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String expectedDisbursementDate;
    Delinquent delinquent;
    String interestRecognitionOnDisbursementDate;
    Options loanScheduleType;
    Options loanScheduleProcessingType;
}