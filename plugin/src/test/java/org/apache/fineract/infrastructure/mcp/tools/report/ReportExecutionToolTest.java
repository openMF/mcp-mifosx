package org.apache.fineract.infrastructure.mcp.tools.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.apache.fineract.infrastructure.mcp.service.McpErrorSanitizer;
import org.apache.fineract.infrastructure.report.service.ReportingProcessService;
import org.apache.fineract.infrastructure.security.service.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ReportExecutionToolTest {

    @Mock
    private ReportingProcessService reportingProcessService;

    @Mock
    private SqlValidator sqlValidator;

    @Mock
    private McpErrorSanitizer sanitizer;

    private ReportExecutionTool tool;

    @BeforeEach
    void setUp() {
        Map<String, ReportingProcessService> reportingProcessServices = Map.of("default", reportingProcessService);
        tool = new ReportExecutionTool(reportingProcessServices, sqlValidator, sanitizer);

        org.mockito.Mockito.lenient().when(sanitizer.sanitize(any(), any())).thenAnswer(invocation -> {
            Exception e = invocation.getArgument(0);
            return e.getMessage();
        });
    }

    @Test
    void runReport_whenReportNameIsNull_throwsException() {
        assertThatThrownBy(() -> tool.runReport(null, "JSON", null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("reportName is required and must not be blank");
    }

    @Test
    void runReport_whenReportNameIsBlank_throwsException() {
        assertThatThrownBy(() -> tool.runReport("   ", "JSON", null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("reportName is required and must not be blank");
    }

    @Test
    void runReport_whenSqlInjectionAttempted_throwsException() {
        // Arrange
        String maliciousName = "report'; DROP TABLE users;--";
        doThrow(new IllegalArgumentException("SQL Injection Detected")).when(sqlValidator).validate(maliciousName);

        // Act & Assert
        assertThatThrownBy(() -> tool.runReport(maliciousName, "JSON", null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SQL Injection Detected");
    }

    @Test
    void runReport_whenValidInput_returnsSuccessResult() {
        // Arrange
        Response mockResponse = Response.ok("Report Data Output").build();
        when(reportingProcessService.processRequest(eq("Active Loans"), any())).thenReturn(mockResponse);

        // Act
        ReportExecutionTool.ReportResult result = tool.runReport("Active Loans", "JSON", "01 Jan 2026", null, 1L, null);

        // Assert
        assertThat(result.reportName()).isEqualTo("Active Loans");
        assertThat(result.outputType()).isEqualTo("JSON");
        assertThat(result.data()).isEqualTo("Report Data Output");
        assertThat(result.message()).isEqualTo("Report executed successfully");
    }
}
