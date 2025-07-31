package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SavingAccountApproval {
    String approvedOnDate	;
    String dateFormat;
    String locale;
    String note;
}