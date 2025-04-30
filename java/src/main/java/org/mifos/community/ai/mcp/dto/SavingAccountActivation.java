package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SavingAccountActivation {
    String activationDate;
    String dateFormat;
    String locale;
    String note;
}
