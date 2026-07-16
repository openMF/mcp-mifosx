package org.apache.fineract.infrastructure.mcp.tools.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.mcp.service.McpErrorSanitizer;
import org.apache.fineract.portfolio.loanaccount.service.LoanWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class LoanTransactionToolTest {

    @Mock
    private LoanWritePlatformService loanWritePlatformService;

    @Mock
    private McpErrorSanitizer sanitizer;

    @InjectMocks
    private LoanTransactionTool tool;

    @BeforeEach
    void setUp() {
        // Mock sanitizer to just throw RuntimeException with the original message for easier testing of guards
        org.mockito.Mockito.lenient().when(sanitizer.sanitize(any(), any())).thenAnswer(invocation -> {
            Exception e = invocation.getArgument(0);
            return e.getMessage();
        });
    }

    @Test
    void processRepayment_whenLoanIdIsNull_throwsException() {
        // Act & Assert
        assertThatThrownBy(() -> tool.processRepayment(null, 100.0, "2026-07-16", 1L, "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("loanId is required");
    }

    @Test
    void processRepayment_whenAmountIsNull_throwsException() {
        // Act & Assert
        assertThatThrownBy(() -> tool.processRepayment(1L, null, "2026-07-16", 1L, "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("amount is required");
    }

    @Test
    void processRepayment_whenAmountIsNegative_throwsException() {
        // Act & Assert
        assertThatThrownBy(() -> tool.processRepayment(1L, -50.0, "2026-07-16", 1L, "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("amount is required, must be a finite number, and greater than zero");
    }

    @Test
    void processRepayment_whenAmountIsZero_throwsException() {
        // Act & Assert
        assertThatThrownBy(() -> tool.processRepayment(1L, 0.0, "2026-07-16", 1L, "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("amount is required, must be a finite number, and greater than zero");
    }

    @Test
    void processRepayment_whenAmountIsNaN_throwsException() {
        // Act & Assert
        assertThatThrownBy(() -> tool.processRepayment(1L, Double.NaN, "2026-07-16", 1L, "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("amount is required, must be a finite number, and greater than zero");
    }

    @Test
    void processRepayment_whenAmountIsPositiveInfinity_throwsException() {
        // Act & Assert
        assertThatThrownBy(() -> tool.processRepayment(1L, Double.POSITIVE_INFINITY, "2026-07-16", 1L, "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("amount is required, must be a finite number, and greater than zero");
    }

    @Test
    void processRepayment_whenValidInput_returnsSuccessResult() {
        // Arrange
        CommandProcessingResult mockResult = CommandProcessingResult.resourceResult(123L);
        when(loanWritePlatformService.makeLoanRepayment(eq(org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType.REPAYMENT), eq(1L), any(), eq(false))).thenReturn(mockResult);

        // Act
        LoanTransactionTool.LoanTransactionResult result = tool.processRepayment(1L, 100.0, "2026-07-16", 1L, "Test Repayment");

        // Assert
        assertThat(result.transactionId()).isEqualTo(123L);
        assertThat(result.loanId()).isEqualTo(1L);
        assertThat(result.amount()).isEqualTo(100.0);
        assertThat(result.transactionType()).isEqualTo("repayment");
        assertThat(result.transactionDate()).isEqualTo("2026-07-16");
    }
}
