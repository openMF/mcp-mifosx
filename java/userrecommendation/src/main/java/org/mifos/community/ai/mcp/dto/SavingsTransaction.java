package org.mifos.community.ai.mcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SavingsTransaction {
    @NotBlank
    String dateFormat;
    @NotBlank
    String locale;
    String note;
    @NotNull
    Integer paymentTypeId;
    @NotNull
    @Positive
    Double transactionAmount;
    @NotBlank
    @Pattern(regexp = "\\d{2} [A-Za-z]+ \\d{4}") // Validate "09 May 2025"
    String transactionDate;
}
