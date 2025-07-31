package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Charge {
    String active;
    String amount;
    Integer chargeAppliesTo;
    Integer chargeCalculationType;
    Integer chargeTimeType;
    String currencyCode;
    String locale;
    String monthDayFormat;
    String name;
    String penalty;
}
