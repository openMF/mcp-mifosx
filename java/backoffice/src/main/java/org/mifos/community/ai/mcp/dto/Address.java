package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties({"addressTypeCodeValueId", "countryCodeValueId", "stateProvinceCodeValueId"})
public class Address {
    @NotNull
    String  addressLine1;
    String  addressLine2;
    String  addressLine3;
    @NotNull
    Integer addressType;
    String  city;
    @NotNull
    Integer countryId;
    @NotNull
    String  postalCode;
    Integer stateProvinceId;

    final Integer   addressTypeCodeValueId = 29;
    final Integer   countryCodeValueId = 28;
    final Integer   stateProvinceCodeValueId = 27;
}
