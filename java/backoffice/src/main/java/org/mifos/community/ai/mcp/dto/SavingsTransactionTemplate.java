package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SavingsTransactionTemplate {
    Integer accountId;
    String accountNo;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String date;
    Currency currency;
    String reversed;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String submittedOnDate;
    String interestedPostedAsOn;
    String isManualTransaction;
    String lienTransaction;
    List<Charge> chargesPaidByData;
    List<PaymentType> paymentTypeOptions;
}
