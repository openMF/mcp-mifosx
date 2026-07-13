package org.apache.fineract.infrastructure.mcp.tools.savings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.mcp.service.McpErrorSanitizer;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool for retrieving savings account details from Apache Fineract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SavingsAccountTool implements FineractMcpTool {

    private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
    private final McpErrorSanitizer mcpErrorSanitizer;

    @Override
    public String getCategory() {
        return "savings";
    }

    @Tool(name = "fineract_savings_get",
          description = "Retrieve detailed information about a savings account in Apache Fineract. "
              + "Returns account balance, currency, interest rate, account status, and client information. "
              + "Use this tool to check a client's savings position before processing deposits or withdrawals.")
    public SavingsDetailResult getSavingsDetails(
            @ToolParam(description = "The unique identifier of the savings account")
            Long savingsId) {

        log.info("MCP Tool: Getting details for savings account ID: {}", savingsId);

        try {
            if (savingsId == null) {
                throw new IllegalArgumentException("savingsId is required");
            }
            SavingsAccountData savingsData = savingsAccountReadPlatformService.retrieveOne(savingsId);

            return new SavingsDetailResult(
                    savingsData.getId(),
                    savingsData.getAccountNo(),
                    savingsData.getClientId(),
                    savingsData.getClientName(),
                    savingsData.getSavingsProductName(),
                    savingsData.getCurrency() != null ? savingsData.getCurrency().getCode() : "Unknown",
                    savingsData.getSummary() != null && savingsData.getSummary().getAccountBalance() != null
                            ? savingsData.getSummary().getAccountBalance().doubleValue() : 0.0,
                    savingsData.getNominalAnnualInterestRate() != null
                            ? savingsData.getNominalAnnualInterestRate().doubleValue() : 0.0,
                    savingsData.getStatus() != null ? savingsData.getStatus().getValue() : "Unknown",
                    savingsData.getActivatedOnDate() != null ? savingsData.getActivatedOnDate().toString() : null
            );

        } catch (Exception e) {
            throw new RuntimeException(mcpErrorSanitizer.sanitize(e, "fineract_savings_get"));
        }
    }

    /**
     * Result record for savings account detail retrieval.
     */
    public record SavingsDetailResult(
            Long id,
            String accountNumber,
            Long clientId,
            String clientName,
            String productName,
            String currency,
            Double accountBalance,
            Double annualInterestRate,
            String status,
            String activationDate
    ) {}
}