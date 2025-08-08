package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransferFunds {
    Integer fromOfficeId;
    Integer fromClientId;
    String fromAccountType;
    String fromAccountId;
    Integer toOfficeId;
    Integer toClientId;
    Integer toAccountType;
    Integer toAccountId;
    String transferDate;
    Double transferAmount;
    String transferDescription;
    String dateFormat = "dd MMMM yyyy";
    String locale = "en";
}