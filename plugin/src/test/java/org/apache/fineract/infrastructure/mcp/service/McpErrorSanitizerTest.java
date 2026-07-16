package org.apache.fineract.infrastructure.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
/**
 * Test suite for {@link McpErrorSanitizer}.
 * Validates that sensitive information (SQL, file paths, PII) is redacted from exceptions
 * and that un-whitelisted exceptions correctly return a generic fallback message.
 */
public class McpErrorSanitizerTest {

    private McpErrorSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new McpErrorSanitizer();
    }

    @Test
    void sanitize_whenIllegalArgumentException_returnsCleanMessageWithoutStackTrace() {
        // Arrange
        String cleanMessage = "amount is required, must be a finite number, and greater than zero";
        IllegalArgumentException exception = new IllegalArgumentException(cleanMessage);

        // Act
        String result = sanitizer.sanitize(exception, "testTool");

        // Assert
        assertThat(result).isEqualTo(cleanMessage);
    }

    @Test
    void sanitize_whenExceptionContainsSql_returnsFallbackMessage() {
        // Arrange
        String exceptionMessage = "Database error SELECT * FROM users";
        IllegalArgumentException exception = new IllegalArgumentException(exceptionMessage);

        // Act
        String result = sanitizer.sanitize(exception, "userTool");

        // Assert
        assertThat(result).isEqualTo("An internal error occurred while processing the request.");
    }

    @Test
    void sanitize_whenExceptionContainsPath_returnsFallbackMessage() {
        // Arrange
        String exceptionMessage = "Failed to open /var/log/app.log";
        IllegalArgumentException exception = new IllegalArgumentException(exceptionMessage);

        // Act
        String result = sanitizer.sanitize(exception, "contactTool");

        // Assert
        assertThat(result).isEqualTo("An internal error occurred while processing the request.");
    }

    @Test
    void sanitize_whenGenericException_returnsFallbackMessage() {
        // Arrange
        NullPointerException exception = new NullPointerException();

        // Act
        String result = sanitizer.sanitize(exception, "testTool");

        // Assert
        assertThat(result).isEqualTo("An internal error occurred while processing the request.");
        assertThat(result).doesNotContain("NullPointerException");
    }
}
