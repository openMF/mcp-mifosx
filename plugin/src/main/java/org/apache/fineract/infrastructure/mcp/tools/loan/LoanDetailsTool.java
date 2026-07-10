package org.apache.fineract.infrastructure.mcp.tools.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.mcp.service.McpErrorSanitizer;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.apache.fineract.portfolio.loanaccount.data.LoanAccountData;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool for retrieving loan account details from Apache Fineract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanDetailsTool implements FineractMcpTool {

    private final LoanReadPlatformService loanReadPlatformService;
    private final McpErrorSanitizer mcpErrorSanitizer;

    @Override
    public String getCategory() {
        return "loan";
    }

    @Tool(name = "fineract_loan_get",
          description = "Retrieve detailed information about a specific loan account in Apache Fineract. "
              + "Returns comprehensive loan data including principal amount, interest rate, term, "
              + "repayment schedule summary, outstanding balance, arrears information, and current status. "
              + "Use this tool to understand a loan's current state before processing transactions.")
    public LoanDetailResult getLoanDetails(
            @ToolParam(description = "The unique identifier of the loan account")
            Long loanId) {

        log.info("MCP Tool: Getting details for loan ID: {}", loanId);

        try {
            LoanAccountData loanData = loanReadPlatformService.retrieveOne(loanId);

            return new LoanDetailResult(
                    loanData.getId(),
                    loanData.getAccountNo(),
                    loanData.getClientId(),
                    loanData.getClientName(),
                    loanData.getLoanProductName(),
                    loanData.getPrincipal() != null ? loanData.getPrincipal().doubleValue() : 0.0,
                    loanData.getAnnualInterestRate() != null ? loanData.getAnnualInterestRate().doubleValue() : 0.0,
                    loanData.getNumberOfRepayments(),
                    loanData.getRepaymentEvery(),
                    loanData.getSummary() != null ? loanData.getSummary().getTotalOutstanding() != null
                            ? loanData.getSummary().getTotalOutstanding().doubleValue() : 0.0 : 0.0,
                    loanData.getTotalOverpaid() != null ? loanData.getTotalOverpaid().doubleValue() : 0.0,
                    loanData.getStatus() != null ? loanData.getStatus().getValue() : "Unknown",
                    loanData.getTimeline() != null && loanData.getTimeline().getActualDisbursementDate() != null ? loanData.getTimeline().getActualDisbursementDate().toString() : null,
                    loanData.getTimeline() != null && loanData.getTimeline().getExpectedMaturityDate() != null ? loanData.getTimeline().getExpectedMaturityDate().toString() : null
            );

        } catch (Exception e) {
            throw new RuntimeException(mcpErrorSanitizer.sanitize(e, "fineract_loan_get"));
        }
    }

    /**
     * Result record for loan detail retrieval.
     */
    public record LoanDetailResult(
            Long id,
            String accountNumber,
            Long clientId,
            String clientName,
            String productName,
            Double principal,
            Double annualInterestRate,
            Integer numberOfRepayments,
            Integer repaymentEvery,
            Double totalOutstanding,
            Double totalOverpaid,
            String status,
            String disbursementDate,
            String maturityDate
    ) {}
}