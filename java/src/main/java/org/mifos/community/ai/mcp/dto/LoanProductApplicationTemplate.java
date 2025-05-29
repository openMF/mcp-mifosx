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
    Types termPeriodFrequencyType;
    Integer numberOfRepayments;
    Integer repaymentEvery;
    Types repaymentFrequencyType;
    Double interestRatePerPeriod;
    Types interestRateFrequencyType;
    Double annualInterestRate;
    String isFloatingInterestRate;
    Types amortizationType;
    Types interestType;
    Types interestCalculationPeriodType;
    String allowPartialPeriodInterestCalculation;
    String transactionProcessingStrategyCode;
    String disallowExpectedDisbursements;
    Timeline timeline;
    List<Charge> charges;
    List<LoanProduct> productOptions;
    List<LoanOfficer> loanOfficerOptions;
    List<Object> loanPurposeOptions;
    List<Object> fundOptions;
    List<Types> termFrequencyTypeOptions;
    List<Types> repaymentFrequencyTypeOptions;
    List<Types> repaymentFrequencyNthDayTypeOptions;
    List<Types> repaymentFrequencyDaysOfWeekTypeOptions;
    List<Types> interestRateFrequencyTypeOptions;
    List<Types> amortizationTypeOptions;
    List<Types> interestTypeOptions;
    List<Types> interestCalculationPeriodTypeOptions;
    List<Options> transactionProcessingStrategyOptions;
    List<Charge> chargeOptions;
    List<Object> loanCollateralOptions;
    List<Types> loanScheduleTypeOptions;
    List<Types> loanScheduleProcessingTypeOptions;
    List<Options> daysInYearCustomStrategyOptions;
    List<Options> accountLinkingOptions;
    String multiDisburseLoan;
    String canDefineInstallmentAmount;
    String isTopup;
    String fraud;
    LoanProduct product;

    List<Charge> overdueCharges;
    Types daysInMonthType;
    Types daysInYearType;
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
    Types loanScheduleType;
    Types loanScheduleProcessingType;
}