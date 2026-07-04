package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SavingsAccount {
    Integer id;
    String accountNo;
    Integer productId;
    String productName;
    String shortProductName;
    Status status;
    Currency currency;
    Types accountType;
    Timeline timeline;
    Status subStatus;
    Types depositType;
}
