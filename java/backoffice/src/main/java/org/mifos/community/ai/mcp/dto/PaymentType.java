package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PaymentType {
    Integer id;
    String name;
    String description;
    String isCashPayment;
    Integer position;
    String codeName;
    String isSystemDefined;
}