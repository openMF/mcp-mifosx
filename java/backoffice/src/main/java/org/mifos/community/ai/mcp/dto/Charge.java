package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Charge {
    String active;
    String amount;
    Integer chargeAppliesTo;
    Integer chargeCalculationType;
    Integer chargePaymentMode;
    Integer chargeTimeType;
    String currencyCode;
    String locale;
    String monthDayFormat;
    String name;
    String penalty;
}
