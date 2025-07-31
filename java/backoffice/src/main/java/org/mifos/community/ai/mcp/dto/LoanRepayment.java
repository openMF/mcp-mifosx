package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoanRepayment {
    String transactionDate;
    Double transactionAmount;
    String externalId;
    Integer paymentTypeId;
    String note;
    String accountNumber;
    String checkNumber;
    String routingCode;
    String receiptNumber;
    String bankNumber;
    String dateFormat = "dd MMMM yyyy";
    String locale = "en";
}
