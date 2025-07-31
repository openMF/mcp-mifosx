package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Currency {
    String code;
    String name;
    Integer decimalPlaces;
    Integer inMultiplesOf;
    String displaySymbol;
    String nameCode;
    String displayLabel;
}