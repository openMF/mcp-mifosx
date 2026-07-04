package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class CurrencyResponse {
    List<Currency> selectedCurrencyOptions;
    List<Currency> currencyOptions;
}
