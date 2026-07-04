package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddLoanProductCollateralRequest {
    Integer collateralTypeId;
    String description;
    String locale;
    String value;
}
