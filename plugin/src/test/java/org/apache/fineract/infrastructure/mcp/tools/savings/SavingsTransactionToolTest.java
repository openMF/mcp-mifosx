package org.apache.fineract.infrastructure.mcp.tools.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.mcp.service.McpErrorSanitizer;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SavingsTransactionToolTest {

    @Mock
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;

    @Mock
    private McpErrorSanitizer sanitizer;

    @InjectMocks
    private SavingsTransactionTool tool;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(sanitizer.sanitize(any(), any())).thenAnswer(invocation -> {
            Exception e = invocation.getArgument(0);
            return e.getMessage();
        });
    }

    // --- DEPOSIT TESTS ---

    @Test
    void processDeposit_whenSavingsIdIsNull_throwsException() {
        assertThatThrownBy(() -> tool.processDeposit(null, 100.0, "2026-07-16", "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("savingsId is required");
    }

    @Test
    void processDeposit_whenAmountIsNaN_throwsException() {
        assertThatThrownBy(() -> tool.processDeposit(1L, Double.NaN, "2026-07-16", "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("amount is required, must be a finite number, and greater than zero");
    }

    @Test
    void processDeposit_whenAmountIsNegative_throwsException() {
        assertThatThrownBy(() -> tool.processDeposit(1L, -10.0, "2026-07-16", "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("amount is required, must be a finite number, and greater than zero");
    }

    @Test
    void processDeposit_whenValidInput_returnsSuccessResult() {
        CommandProcessingResult mockResult = CommandProcessingResult.resourceResult(123L);
        when(savingsAccountWritePlatformService.deposit(eq(1L), any())).thenReturn(mockResult);

        SavingsTransactionTool.SavingsTransactionResult result = tool.processDeposit(1L, 100.0, "2026-07-16", "Test");

        assertThat(result.transactionId()).isEqualTo(123L);
        assertThat(result.savingsId()).isEqualTo(1L);
        assertThat(result.amount()).isEqualTo(100.0);
        assertThat(result.transactionType()).isEqualTo("deposit");
    }

    // --- WITHDRAWAL TESTS ---

    @Test
    void processWithdrawal_whenSavingsIdIsNull_throwsException() {
        assertThatThrownBy(() -> tool.processWithdrawal(null, 100.0, "2026-07-16", "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("savingsId is required");
    }

    @Test
    void processWithdrawal_whenAmountIsPositiveInfinity_throwsException() {
        assertThatThrownBy(() -> tool.processWithdrawal(1L, Double.POSITIVE_INFINITY, "2026-07-16", "Note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("amount is required, must be a finite number, and greater than zero");
    }

    @Test
    void processWithdrawal_whenValidInput_returnsSuccessResult() {
        CommandProcessingResult mockResult = CommandProcessingResult.resourceResult(456L);
        when(savingsAccountWritePlatformService.withdrawal(eq(1L), any())).thenReturn(mockResult);

        SavingsTransactionTool.SavingsTransactionResult result = tool.processWithdrawal(1L, 50.0, "2026-07-16", "Test");

        assertThat(result.transactionId()).isEqualTo(456L);
        assertThat(result.savingsId()).isEqualTo(1L);
        assertThat(result.amount()).isEqualTo(50.0);
        assertThat(result.transactionType()).isEqualTo("withdrawal");
    }
}
