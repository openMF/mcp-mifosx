package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class SavingProductApplication {
    Integer productId;
    String submittedOnDate;
    Integer fieldOfficerId;
    String externalId;
    double nominalAnnualInterestRate;
    Integer interestCompoundingPeriodType;
    Integer interestPostingPeriodType;
    Integer interestCalculationType;
    Integer interestCalculationDaysInYearType;
    String withdrawalFeeForTransfers;
    Integer lockinPeriodFrequency;
    Integer lockinPeriodFrequencyType;
    String allowOverdraft;
    String enforceMinRequiredBalance;
    List<Charge> charges;
    String dateFormat;
    String monthDayFormat;
    String locale;
    Integer clientId;
}
