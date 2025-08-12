package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;

@Getter
@Setter
public class ClientAccounts {
    ArrayList<LoanAccount> loanAccounts;
    ArrayList<Object> groupLoanIndividualMonitoringAccounts;
    ArrayList<SavingsAccount> savingsAccounts;
    ArrayList<Object> guarantorAccounts;
}
