package org.apache.fineract.infrastructure.mcp.tools.report;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.apache.fineract.infrastructure.report.service.ReportingProcessService;
import org.apache.fineract.infrastructure.security.utils.SQLInjectionValidator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP Tool for executing reports in Apache Fineract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportExecutionTool implements FineractMcpTool {

    private final Map<String, ReportingProcessService> reportingProcessServices;

    @Override
    public String getCategory() {
        return "report";
    }

    @Tool(name = "fineract_report_run",
          description = "Execute a report in Apache Fineract by name. Reports can be parameterized with "
              + "date ranges, office IDs, loan officer IDs, and other filters. Returns the report data "
              + "in a structured format. Use this tool to generate business intelligence reports, "
              + "portfolio summaries, or operational dashboards.")
    public ReportResult runReport(
            @ToolParam(description = "The exact name of the report to execute (e.g., 'Active Loans - Details')")
            String reportName,
            @ToolParam(description = "Output format: 'JSON', 'CSV', 'PDF', 'XLSX', or 'HTML' (default: JSON)")
            String outputType,
            @ToolParam(description = "Start date for the report period in dd MMMM yyyy format (optional)", required = false)
            String startDate,
            @ToolParam(description = "End date for the report period in dd MMMM yyyy format (optional)", required = false)
            String endDate,
            @ToolParam(description = "Office ID to filter the report (optional)", required = false)
            Long officeId,
            @ToolParam(description = "Loan officer ID to filter the report (optional)", required = false)
            Long loanOfficerId) {

        log.info("MCP Tool: Running report '{}' with output type '{}'", reportName, outputType);

        // Validate report name to prevent SQL injection
        SQLInjectionValidator.validateReportInput(reportName);

        try {
            // Build query parameters
            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("output-type", outputType != null ? outputType : "JSON");

            if (startDate != null && !startDate.isBlank()) {
                queryParams.put("R_startDate", startDate);
            }
            if (endDate != null && !endDate.isBlank()) {
                queryParams.put("R_endDate", endDate);
            }
            if (officeId != null) {
                queryParams.put("R_officeId", officeId.toString());
            }
            if (loanOfficerId != null) {
                queryParams.put("R_loanOfficerId", loanOfficerId.toString());
            }

            // Find the appropriate reporting service
            ReportingProcessService reportService = reportingProcessServices.values().stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No reporting service available"));

            // Convert map to MultivaluedMap for the reporting service
            jakarta.ws.rs.core.MultivaluedMap<String, String> multivaluedParams =
                    new jakarta.ws.rs.core.MultivaluedHashMap<>();
            queryParams.forEach(multivaluedParams::putSingle);

            // Execute the report
            jakarta.ws.rs.core.Response response = reportService.processRequest(reportName, multivaluedParams);

            Object reportData = response.getEntity();

            return new ReportResult(
                    reportName,
                    outputType != null ? outputType : "JSON",
                    reportData != null ? reportData.toString() : "No data returned",
                    "Report executed successfully"
            );

        } catch (Exception e) {
            log.error("Error executing report '{}'", reportName, e);
            throw new RuntimeException("Failed to execute report: " + e.getMessage(), e);
        }
    }

    /**
     * Result record for report execution.
     */
    public record ReportResult(
            String reportName,
            String outputType,
            String data,
            String message
    ) {}
}