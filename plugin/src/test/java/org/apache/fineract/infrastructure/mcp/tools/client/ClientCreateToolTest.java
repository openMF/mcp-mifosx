package org.apache.fineract.infrastructure.mcp.tools.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.mcp.service.McpErrorSanitizer;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ClientCreateToolTest {

    @Mock
    private ClientWritePlatformService clientWritePlatformService;

    @Mock
    private McpErrorSanitizer sanitizer;

    @InjectMocks
    private ClientCreateTool tool;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(sanitizer.sanitize(any(), any())).thenAnswer(invocation -> {
            Exception e = invocation.getArgument(0);
            return e.getMessage();
        });
    }

    @Test
    void createClient_whenFirstnameIsNull_throwsException() {
        assertThatThrownBy(() -> tool.createClient(null, "Doe", 1L, null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("firstName is required");
    }

    @Test
    void createClient_whenLastnameIsBlank_throwsException() {
        assertThatThrownBy(() -> tool.createClient("John", "   ", 1L, null, null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("lastName is required");
    }

    @Test
    void createClient_whenValidInput_returnsSuccessResult() {
        CommandProcessingResult mockResult = CommandProcessingResult.resourceResult(123L);
        when(clientWritePlatformService.createClient(any())).thenReturn(mockResult);

        ClientCreateTool.ClientCreateResult result = tool.createClient("John", "Doe", 1L, null, null, null, null);

        assertThat(result.clientId()).isEqualTo(123L);
        assertThat(result.message()).isEqualTo("Client created successfully");
    }
}
