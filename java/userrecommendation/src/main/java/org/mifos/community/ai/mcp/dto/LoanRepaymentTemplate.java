package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class LoanRepaymentTemplate {
    LoanTransactionType type;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String date;
    Currency currency;
    Double amount;
    Double netDisbursalAmount;
    Double principalPortion;
    Double interestPortion;
    Integer feeChargesPortion;
    Integer penaltyChargesPortion;
    String manuallyReversed;
    List<PaymentType> paymentTypeOptions;
    Integer numberOfRepayments;
}
