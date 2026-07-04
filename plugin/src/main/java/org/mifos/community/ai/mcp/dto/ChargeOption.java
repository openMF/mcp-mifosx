package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChargeOption {
    Integer id;
    String name;
    String active;
    String penalty;
    String freeWithdrawal;
    String isPaymentType;
    Integer freeWithdrawalChargeFrequency;
    Integer restartFrequency;
    Integer restartFrequencyEnum;
    Currency currency;
    Double amount;
    Types chargeTimeType;
    Types chargeAppliesTo;
    Types chargeCalculationType;
    Types chargePaymentMode;
}
