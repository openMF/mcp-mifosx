package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoanTransactionType {
    Integer id;
    String code;
    String value;
    String disbursement;
    String repaymentAtDisbursement;
    String repayment;
    String merchantIssuedRefund;
    String payoutRefund;
    String goodwillCredit;
    String interestPaymentWaiver;
    String chargeRefund;
    String contra;
    String waiveInterest;
    String waiveCharges;
    String accrual;
    String writeOff;
    String recoveryRepayment;
    String initiateTransfer;
    String approveTransfer;
    String withdrawTransfer;
    String rejectTransfer;
    String chargePayment;
    String refund;
    String refundForActiveLoans;
    String creditBalanceRefund;
    String chargeAdjustment;
    String chargeback;
    String chargeoff;
    String downPayment;
    String reAge;
    String reAmortize;
    String accrualActivity;
    String interestRefund;
    String accrualAdjustment;
    String capitalizedIncome;
    String capitalizedIncomeAmortization;
    String capitalizedIncomeAdjustment;
    String capitalizedIncomeAmortizationAdjustment;
}
