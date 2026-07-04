package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoanAccountApprovalTemplate {
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String approvalDate;
    Double approvalAmount;
    Double netDisbursalAmount;
    Currency currency;
}
