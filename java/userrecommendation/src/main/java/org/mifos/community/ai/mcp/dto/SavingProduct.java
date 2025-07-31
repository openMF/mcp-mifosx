package org.mifos.community.ai.mcp.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class SavingProduct {
    @NonNull
    String name;
    @NonNull
    @Size(min = 4, max = 4)
    String shortName;
    String description;
    @NotNull
    String currencyCode;
    Integer digitsAfterDecimal;
    Integer inMultiplesOf;
    double nominalAnnualInterestRate;
    Integer interestCompoundingPeriodType;
    Integer interestPostingPeriodType;
    Integer interestCalculationType;
    Integer interestCalculationDaysInYearType;
    String withdrawalFeeForTransfers;
    String enforceMinRequiredBalance;
    String allowOverdraft;
    String withHoldTax;
    String isDormancyTrackingActive;
    List<Charge> charges;
    Integer accountingRule;
    String locale;
}
