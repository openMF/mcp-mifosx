// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts Fineract internal service results into safe, predictable
 * JSON-compatible responses for MCP clients.
 * <p>
 * Key responsibilities:
 * <ul>
 *   <li>Convert Fineract data objects to JSON nodes</li>
 *   <li>Strip internal details (stack traces, secrets, tenant config)</li>
 *   <li>Produce consistent error responses</li>
 *   <li>Preserve response shape matching existing java/backoffice output</li>
 * </ul>
 * <p>
 * <strong>Security:</strong> This mapper MUST NOT leak:
 * <ul>
 *   <li>Internal stack traces or exception details</li>
 *   <li>Secrets, tokens, or credentials</li>
 *   <li>Tenant configuration details</li>
 *   <li>Raw database error messages</li>
 * </ul>
 */
public class ResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(ResponseMapper.class);
    private final ObjectMapper objectMapper;

    /**
     * Default constructor that creates a default {@link ObjectMapper}.
     */
    public ResponseMapper() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Constructor that uses the provided {@link ObjectMapper}.
     *
     * @param objectMapper the ObjectMapper to use for serialization and deserialization
     */
    public ResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Convert a Fineract service result to a JSON node suitable for MCP response.
     * <p>
     * If the object is already a JsonNode, it is returned directly.
     * Otherwise, it is serialized to JSON and back to a JsonNode.
     *
     * @param data the Fineract service result (ClientData, CommandProcessingResult, etc.)
     * @return a safe JSON node for the MCP client
     */
    public JsonNode toJsonNode(Object data) {
        if (data == null) {
            return objectMapper.createObjectNode();
        }
        if (data instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Fineract response to JSON", e);
            return createErrorResponse("Internal error: failed to serialize response");
        }
    }

    /**
     * Create a safe error response that does not leak internal details.
     *
     * @param userMessage a human-readable error message for the MCP client
     * @return an error JSON node
     */
    public JsonNode createErrorResponse(String userMessage) {
        ObjectNode errorNode = objectMapper.createObjectNode();
        errorNode.put("error", true);
        errorNode.put("message", userMessage);
        return errorNode;
    }

    /**
     * Create a safe error response from an exception, stripping internal details.
     * <p>
     * Only the exception class name and a sanitized message are included.
     * Stack traces, cause chains, and internal details are NOT exposed.
     *
     * @param e the exception
     * @param context a brief context string (e.g., "getClientDetailsById")
     * @return a safe error JSON node
     */
    public JsonNode createErrorResponse(Exception e, String context) {
        String rawMessage = e.getMessage();
        String sanitizedMessage = sanitizeErrorMessage(rawMessage);
        
        log.warn("Error in MCP tool [{}]: {} - {}", context, e.getClass().getSimpleName(), sanitizedMessage);

        String safeMessage;
        if (isWhitelistedException(e)) {
            safeMessage = sanitizedMessage;
        } else {
            safeMessage = "An unexpected error occurred. Please contact your administrator.";
        }

        ObjectNode errorNode = objectMapper.createObjectNode();
        errorNode.put("error", true);
        errorNode.put("context", context);
        errorNode.put("message", safeMessage);
        return errorNode;
    }

    /**
     * Check if the exception is an approved domain/validation exception whose message
     * is safe to show to the client (after sanitization).
     */
    private boolean isWhitelistedException(Exception e) {
        if (e == null) {
            return false;
        }
        String className = e.getClass().getName();
        return className.endsWith("NotFoundException")
                || className.endsWith("ResourceNotFoundException")
                || className.endsWith("DomainRuleException")
                || className.endsWith("PlatformDataIntegrityException")
                || className.contains("ValidationException")
                || className.contains("BusinessRuleException")
                || className.contains("AccessDeniedException")
                || className.contains("InvalidParameterException");
    }

    /**
     * Sanitize an error message to remove potential sensitive information.
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "An unexpected error occurred";
        }

        // 1. Check for database/SQL-related error patterns and redact them entirely
        String lowerMessage = message.toLowerCase();
        if (lowerMessage.contains("sql") || lowerMessage.contains("jdbc") || lowerMessage.contains("connection")
                || lowerMessage.contains("select") || lowerMessage.contains("insert") || lowerMessage.contains("update")
                || lowerMessage.contains("delete") || lowerMessage.contains("constraint") || lowerMessage.contains("foreign key")
                || lowerMessage.contains("database") || lowerMessage.contains("hibernate") || lowerMessage.contains("persistence")) {
            return "A database error occurred while processing the request.";
        }

        // 2. Remove file paths (Linux/macOS absolute paths, Windows paths)
        message = message.replaceAll("(?:/[a-zA-Z0-9_.-]+)+", "[PATH]");
        message = message.replaceAll("[a-zA-Z]:\\\\(?:[a-zA-Z0-9_.-]+\\\\)*[a-zA-Z0-9_.-]*", "[PATH]");

        // 3. Remove credentials, secrets, tokens, keys
        message = message.replaceAll("(?i)(jdbc|password|secret|token|key|credential|auth)\\s*[:=]\\s*\\S+", "[REDACTED]");

        // 4. Remove potential PII like email addresses and phone numbers
        message = message.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "[EMAIL]");
        message = message.replaceAll("\\+?\\d{1,4}[- .]?\\(?\\d{1,3}\\)?[- .]?\\d{1,4}[- .]?\\d{1,4}[- .]?\\d{1,9}", "[PHONE]");

        // 5. Remove tenant details (e.g., Fineract-Platform-TenantId)
        message = message.replaceAll("(?i)tenant\\S*", "[TENANT]");

        // Truncate excessively long messages
        if (message.length() > 200) {
            message = message.substring(0, 200) + "...";
        }

        return message.trim();
    }
}
