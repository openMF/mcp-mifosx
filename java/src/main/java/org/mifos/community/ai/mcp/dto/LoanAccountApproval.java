package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoanAccountApproval {
    String approvedOnDate;
    String expectedDisbursementDate;
    Double approvedLoanAmount;
    String note;
    String dateFormat = "dd MMMM yyyy";
    String locale;
}
