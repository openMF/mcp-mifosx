package org.apache.fineract.infrastructure.mcp.tools.loan;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.mcp.service.McpErrorSanitizer;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.apache.fineract.portfolio.loanaccount.service.LoanApplicationWritePlatformService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool for approving loan applications in Apache Fineract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanApprovalTool implements FineractMcpTool {

    private final LoanApplicationWritePlatformService loanApplicationWritePlatformService;
    private final McpErrorSanitizer mcpErrorSanitizer;

    @Override
    public String getCategory() {
        return "loan";
    }

    @Tool(name = "fineract_loan_approve",
          description = "Approve a pending loan application in Apache Fineract. The loan must be in 'Submitted and pending approval' "
              + "status. Requires the loan ID and optionally an approval date and approved amount (if different from applied amount). "
              + "Returns the approval confirmation with the loan ID and new status.")
    public LoanApprovalResult approveLoan(
            @ToolParam(description = "The unique identifier of the loan application to approve")
            Long loanId,
            @ToolParam(description = "The approval date in yyyy-MM-dd format (defaults to today)", required = false)
            String approvalDate,
            @ToolParam(description = "The approved principal amount (defaults to applied amount)", required = false)
            Double approvedAmount,
            @ToolParam(description = "Optional note or reason for approval", required = false)
            String note) {

        log.info("MCP Tool: Approving loan ID: {}", loanId);

        try {
            if (loanId == null) {
                throw new IllegalArgumentException("loanId is required");
            }
            LocalDate approveDate = (approvalDate != null && !approvalDate.isBlank())
                    ? LocalDate.parse(approvalDate)
                    : LocalDate.now();

            var commandJson = new java.util.HashMap<String, Object>();
            commandJson.put("locale", "en");
            commandJson.put("dateFormat", "dd MMMM yyyy");
            commandJson.put("approvedOnDate", approveDate.format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy")));

            if (approvedAmount != null) {
                commandJson.put("approvedLoanAmount", approvedAmount.toString());
            }
            if (note != null && !note.isBlank()) {
                commandJson.put("note", note);
            }

            CommandProcessingResult result = loanApplicationWritePlatformService.approveApplication(
                    loanId,
                    org.apache.fineract.infrastructure.core.api.JsonCommand.fromJsonElement(
                            loanId,
                            new com.google.gson.JsonParser().parse(new com.google.gson.Gson().toJson(commandJson)).getAsJsonObject()
                    )
            );

            return new LoanApprovalResult(
                    result.getResourceId(),
                    "Approved",
                    approveDate.toString(),
                    approvedAmount != null ? approvedAmount : null,
                    "Loan approved successfully"
            );

        } catch (Exception e) {
            throw new RuntimeException(mcpErrorSanitizer.sanitize(e, "fineract_loan_approve"));
        }
    }

    /**
     * Result record for loan approval.
     */
    public record LoanApprovalResult(
            Long loanId,
            String status,
            String approvalDate,
            Double approvedAmount,
            String message
    ) {}
}