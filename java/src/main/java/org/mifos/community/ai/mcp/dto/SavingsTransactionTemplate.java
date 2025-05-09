package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SavingsTransactionTemplate {
    Integer accountId;
    String accountNo;
    String date;
    Currency currency;
    String reversed;
    String submittedOnDate;
    String interestedPostedAsOn;
    String isManualTransaction;
    String lienTransaction;
    List<Charge> chargesPaidByData;
    List<PaymentType> paymentTypeOptions;
}
