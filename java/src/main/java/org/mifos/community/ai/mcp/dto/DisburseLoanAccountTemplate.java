package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DisburseLoanAccountTemplate {
    LoanTransactionType type;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String date;
    Currency currency;
    Double amount;
    Double netDisbursalAmount;
    String manuallyReversed;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String possibleNextRepaymentDate;
    List<PaymentType> paymentTypeOptions;
    Integer numberOfRepayments;
    List<LoanRepaymentInstallment> loanRepaymentScheduleInstallments;
}
