package org.apache.fineract.infrastructure.mcp.tools.savings;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool for processing savings account transactions (deposits and withdrawals) in Apache Fineract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SavingsTransactionTool implements FineractMcpTool {

    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;

    @Override
    public String getCategory() {
        return "savings";
    }

    @Tool(name = "fineract_savings_deposit",
          description = "Process a deposit transaction on an active savings account in Apache Fineract. "
              + "Requires the savings account ID, deposit amount, and transaction date. "
              + "Returns the transaction ID and updated account balance.")
    public SavingsTransactionResult processDeposit(
            @ToolParam(description = "The unique identifier of the savings account")
            Long savingsId,
            @ToolParam(description = "The deposit amount in the account's currency")
            Double amount,
            @ToolParam(description = "The transaction date in yyyy-MM-dd format (defaults to today)")
            String transactionDate,
            @ToolParam(description = "Optional note or reference for the transaction", required = false)
            String note) {

        log.info("MCP Tool: Processing deposit of {} for savings account ID: {}", amount, savingsId);

        try {
            LocalDate txnDate = (transactionDate != null && !transactionDate.isBlank())
                    ? LocalDate.parse(transactionDate)
                    : LocalDate.now();

            var commandJson = new java.util.HashMap<String, Object>();
            commandJson.put("locale", "en");
            commandJson.put("dateFormat", "dd MMMM yyyy");
            commandJson.put("transactionDate", txnDate.format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy")));
            commandJson.put("transactionAmount", amount.toString());

            if (note != null && !note.isBlank()) {
                commandJson.put("note", note);
            }

            CommandProcessingResult result = savingsAccountWritePlatformService.deposit(
                    savingsId,
                    org.apache.fineract.infrastructure.core.api.JsonCommand.fromJsonElement(
                            savingsId,
                            new com.google.gson.JsonParser().parse(new com.google.gson.Gson().toJson(commandJson)).getAsJsonObject()
                    )
            );

            return new SavingsTransactionResult(
                    result.getResourceId(),
                    savingsId,
                    "deposit",
                    amount,
                    txnDate.toString(),
                    "Deposit processed successfully"
            );

        } catch (Exception e) {
            log.error("Error processing deposit for savings account ID: {}", savingsId, e);
            throw new RuntimeException("Failed to process deposit: " + e.getMessage(), e);
        }
    }

    @Tool(name = "fineract_savings_withdrawal",
          description = "Process a withdrawal transaction on an active savings account in Apache Fineract. "
              + "Requires the savings account ID, withdrawal amount, and transaction date. "
              + "The account must have sufficient balance. Returns the transaction ID and updated balance.")
    public SavingsTransactionResult processWithdrawal(
            @ToolParam(description = "The unique identifier of the savings account")
            Long savingsId,
            @ToolParam(description = "The withdrawal amount in the account's currency")
            Double amount,
            @ToolParam(description = "The transaction date in yyyy-MM-dd format (defaults to today)")
            String transactionDate,
            @ToolParam(description = "Optional note or reference for the transaction", required = false)
            String note) {

        log.info("MCP Tool: Processing withdrawal of {} for savings account ID: {}", amount, savingsId);

        try {
            LocalDate txnDate = (transactionDate != null && !transactionDate.isBlank())
                    ? LocalDate.parse(transactionDate)
                    : LocalDate.now();

            var commandJson = new java.util.HashMap<String, Object>();
            commandJson.put("locale", "en");
            commandJson.put("dateFormat", "dd MMMM yyyy");
            commandJson.put("transactionDate", txnDate.format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy")));
            commandJson.put("transactionAmount", amount.toString());

            if (note != null && !note.isBlank()) {
                commandJson.put("note", note);
            }

            CommandProcessingResult result = savingsAccountWritePlatformService.withdrawal(
                    savingsId,
                    org.apache.fineract.infrastructure.core.api.JsonCommand.fromJsonElement(
                            savingsId,
                            new com.google.gson.JsonParser().parse(new com.google.gson.Gson().toJson(commandJson)).getAsJsonObject()
                    )
            );

            return new SavingsTransactionResult(
                    result.getResourceId(),
                    savingsId,
                    "withdrawal",
                    amount,
                    txnDate.toString(),
                    "Withdrawal processed successfully"
            );

        } catch (Exception e) {
            log.error("Error processing withdrawal for savings account ID: {}", savingsId, e);
            throw new RuntimeException("Failed to process withdrawal: " + e.getMessage(), e);
        }
    }

    /**
     * Result record for savings transactions.
     */
    public record SavingsTransactionResult(
            Long transactionId,
            Long savingsId,
            String transactionType,
            Double amount,
            String transactionDate,
            String message
    ) {}
}