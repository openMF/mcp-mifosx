package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoanRepaymentInstallment {
    Integer id;
    Integer installmentId;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String date;
    Double amount;
}
