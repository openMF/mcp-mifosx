package org.apache.fineract.infrastructure.mcp.service;

import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Centralized error sanitizer for MCP tool responses.
 *
 * <p>Intercepts all exceptions thrown by Fineract backend services and produces
 * a clean, safe error message suitable for returning to the MCP client (LLM agent).
 *
 * <p>Security guarantees:
 * <ul>
 *   <li>SQL queries and fragments are never exposed</li>
 *   <li>Absolute file/class paths are never exposed</li>
 *   <li>Only whitelisted domain exception messages pass through</li>
 *   <li>Unknown/fatal exceptions produce a generic safe fallback</li>
 * </ul>
 */
@Service
@Slf4j
public class McpErrorSanitizer {

    private static final String GENERIC_ERROR_MESSAGE =
            "An internal error occurred while processing the request.";

    /**
     * Whitelisted exception class name suffixes. Only exceptions whose simple class name
     * ends with one of these suffixes will have their original message passed through.
     */
    private static final Set<String> WHITELISTED_EXCEPTION_SUFFIXES = Set.of(
            "NotFoundException",
            "AccessDeniedException",
            "PlatformApiDataValidationException",
            "InvalidParameterException",
            "UnsupportedParameterException",
            "PlatformDataIntegrityException",
            "PlatformServiceUnavailableException",
            "UnrecognizedQueryParamException",
            "InsufficientAccountBalanceException",
            "LoanTransactionProcessingException",
            "IllegalArgumentException"
    );

    // Patterns that indicate SQL content leaking through
    private static final Pattern SQL_PATTERN = Pattern.compile(
            "(?i)(SELECT\\s|INSERT\\s|UPDATE\\s|DELETE\\s|DROP\\s|ALTER\\s|CREATE\\s|TRUNCATE\\s"
                    + "|FROM\\s+\\w+\\.\\w+|JOIN\\s|WHERE\\s|GROUP\\s+BY|ORDER\\s+BY|HAVING\\s"
                    + "|sql|jdbc|hibernate|prepared\\s*statement)",
            Pattern.CASE_INSENSITIVE
    );

    // Patterns that indicate absolute file paths or Java class paths
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "(/[a-zA-Z0-9_.-]+){3,}|([a-zA-Z]:\\\\[^\\s]+)"
                    + "|([a-zA-Z_$][a-zA-Z0-9_$]*\\.){3,}[a-zA-Z_$][a-zA-Z0-9_$]*"
    );

    // Patterns that indicate stack trace fragments
    private static final Pattern STACK_TRACE_PATTERN = Pattern.compile(
            "at\\s+[a-zA-Z0-9_$.]+\\([^)]*\\)|Caused\\s+by:|Exception\\s+in\\s+thread"
    );

    /**
     * Sanitizes an exception into a safe error message for MCP responses.
     *
     * @param e        the exception to sanitize
     * @param toolName a short label for the tool (used in logging only, never exposed)
     * @return a sanitized error string safe to return to the MCP client
     */
    public String sanitize(Exception e, String toolName) {
        if (e == null) {
            log.error("MCP tool '{}' encountered a null exception", toolName);
            return GENERIC_ERROR_MESSAGE;
        }

        // Log safe details at ERROR level, and keep the full trace at DEBUG to prevent PII leaks in standard logs
        log.error("MCP tool '{}' encountered an error of type: {}", toolName, e.getClass().getSimpleName());
        log.debug("Full exception trace for MCP tool '{}'", toolName, e);

        // Check if the exception type is whitelisted
        String exceptionClassName = e.getClass().getSimpleName();
        boolean isWhitelisted = WHITELISTED_EXCEPTION_SUFFIXES.stream()
                .anyMatch(exceptionClassName::endsWith);

        if (!isWhitelisted) {
            return GENERIC_ERROR_MESSAGE;
        }

        // The exception type is whitelisted — scrub the message content
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return GENERIC_ERROR_MESSAGE;
        }

        // Even for whitelisted exceptions, scrub SQL/path/stack-trace content
        if (SQL_PATTERN.matcher(message).find()) {
            log.warn("Scrubbed SQL content from whitelisted exception in tool '{}'", toolName);
            return GENERIC_ERROR_MESSAGE;
        }
        if (PATH_PATTERN.matcher(message).find()) {
            log.warn("Scrubbed path/class content from whitelisted exception in tool '{}'", toolName);
            return GENERIC_ERROR_MESSAGE;
        }
        if (STACK_TRACE_PATTERN.matcher(message).find()) {
            log.warn("Scrubbed stack trace content from whitelisted exception in tool '{}'", toolName);
            return GENERIC_ERROR_MESSAGE;
        }

        return message;
    }
}
