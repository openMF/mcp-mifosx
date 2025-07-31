package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Changes {
    String accountNumber;
    String checkNumber;
    String routingCode;
    String receiptNumber;
    String bankNumber;
    Integer paymentTypeId;
    String locale = "en";
    String dateFormat = "dd MMMM yyyy";
    String actualDisbursementDate;
    LoanStatus status;
    String note;
}