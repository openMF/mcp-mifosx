package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoanAccount {
    Integer id;
    String accountNo;
    Integer productId;
    String productName;
    String shortProductName;
    Status status;
    Currency currency;
    Types loanType;
    Timeline timeline;
    String inArrears;
}
