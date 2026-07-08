package org.apache.fineract.infrastructure.mcp.tools.loan;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.apache.fineract.portfolio.loanaccount.service.LoanWritePlatformService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool for processing loan transactions (repayments, waivers, etc.) in Apache Fineract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanTransactionTool implements FineractMcpTool {

    private final LoanWritePlatformService loanWritePlatformService;

    @Override
    public String getCategory() {
        return "loan";
    }

    @Tool(name = "fineract_loan_repayment",
          description = "Process a repayment transaction on an active loan account in Apache Fineract. "
              + "Requires the loan ID, transaction amount, and transaction date. The loan must be in 'Active' status. "
              + "Returns the transaction ID and updated loan balance information.")
    public LoanTransactionResult processRepayment(
            @ToolParam(description = "The unique identifier of the loan account")
            Long loanId,
            @ToolParam(description = "The repayment amount in the loan's currency")
            Double amount,
            @ToolParam(description = "The transaction date in yyyy-MM-dd format (defaults to today)")
            String transactionDate,
            @ToolParam(description = "Optional payment type ID (e.g., cash, bank transfer)", required = false)
            Long paymentTypeId,
            @ToolParam(description = "Optional note or reference for the transaction", required = false)
            String note) {

        log.info("MCP Tool: Processing repayment of {} for loan ID: {}", amount, loanId);

        try {
            LocalDate txnDate = (transactionDate != null && !transactionDate.isBlank())
                    ? LocalDate.parse(transactionDate)
                    : LocalDate.now();

            var commandJson = new java.util.HashMap<String, Object>();
            commandJson.put("locale", "en");
            commandJson.put("dateFormat", "dd MMMM yyyy");
            commandJson.put("transactionDate", txnDate.format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy")));
            commandJson.put("transactionAmount", amount.toString());

            if (paymentTypeId != null) {
                commandJson.put("paymentTypeId", paymentTypeId);
            }
            if (note != null && !note.isBlank()) {
                commandJson.put("note", note);
            }

            CommandProcessingResult result = loanWritePlatformService.makeLoanRepayment(
                    org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType.REPAYMENT,
                    loanId,
                    org.apache.fineract.infrastructure.core.api.JsonCommand.fromJsonElement(
                            loanId,
                            new com.google.gson.JsonParser().parse(new com.google.gson.Gson().toJson(commandJson)).getAsJsonObject()
                    ),
                    false
            );

            return new LoanTransactionResult(
                    result.getResourceId(),
                    loanId,
                    "repayment",
                    amount,
                    txnDate.toString(),
                    "Repayment processed successfully"
            );

        } catch (Exception e) {
            log.error("Error processing repayment for loan ID: {}", loanId, e);
            throw new RuntimeException("Failed to process repayment: " + e.getMessage(), e);
        }
    }

    /**
     * Result record for loan transactions.
     */
    public record LoanTransactionResult(
            Long transactionId,
            Long loanId,
            String transactionType,
            Double amount,
            String transactionDate,
            String message
    ) {}
}