// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mifos.community.ai.mcp.plugin.fineract.ClientReadPlatformService;
import org.mifos.community.ai.mcp.plugin.fineract.PlatformSecurityContext;
import org.mifos.community.ai.mcp.plugin.mapper.ResponseMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ClientServiceAdapter}.
 * <p>
 * Verifies that the adapter:
 * <ol>
 *   <li>Calls PlatformSecurityContext BEFORE the read service (RBAC enforcement)</li>
 *   <li>Delegates to ClientReadPlatformService.retrieveOne()</li>
 *   <li>Returns safe JSON via ResponseMapper</li>
 *   <li>Handles errors gracefully without leaking internals</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ClientServiceAdapterTest {

    @Mock
    private ClientReadPlatformService clientReadPlatformService;

    @Mock
    private PlatformSecurityContext securityContext;

    private ResponseMapper responseMapper;
    private ClientServiceAdapter adapter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        responseMapper = new ResponseMapper(objectMapper);
        adapter = new ClientServiceAdapter(clientReadPlatformService, securityContext, responseMapper);
    }

    @Test
    @DisplayName("getClientDetailsById calls security context BEFORE read service")
    void getClientDetailsById_checksPermissionFirst() {
        // Given
        Long clientId = 1L;
        ObjectNode mockClientData = objectMapper.createObjectNode();
        mockClientData.put("id", 1);
        mockClientData.put("displayName", "John Doe");
        when(clientReadPlatformService.retrieveOne(clientId)).thenReturn(mockClientData);

        // When
        adapter.getClientDetailsById(clientId);

        // Then — verify security check happens BEFORE read service call
        InOrder inOrder = inOrder(securityContext, clientReadPlatformService);
        inOrder.verify(securityContext).validateAccessRights("READ_CLIENT");
        inOrder.verify(clientReadPlatformService).retrieveOne(clientId);
    }

    @Test
    @DisplayName("getClientDetailsById returns client data as JSON")
    void getClientDetailsById_returnsClientData() {
        // Given
        Long clientId = 42L;
        ObjectNode mockClientData = objectMapper.createObjectNode();
        mockClientData.put("id", 42);
        mockClientData.put("displayName", "Jane Smith");
        mockClientData.put("accountNo", "00000042");
        mockClientData.put("officeId", 1);
        when(clientReadPlatformService.retrieveOne(clientId)).thenReturn(mockClientData);

        // When
        JsonNode result = adapter.getClientDetailsById(clientId);

        // Then
        assertNotNull(result);
        assertEquals(42, result.get("id").asInt());
        assertEquals("Jane Smith", result.get("displayName").asText());
        assertEquals("00000042", result.get("accountNo").asText());
    }

    @Test
    @DisplayName("getClientDetailsById returns error when permission denied")
    void getClientDetailsById_handlesPermissionDenied() {
        // Given
        Long clientId = 1L;
        doThrow(new TestAccessDeniedException("User does not have READ_CLIENT permission"))
                .when(securityContext).validateAccessRights("READ_CLIENT");

        // When
        JsonNode result = adapter.getClientDetailsById(clientId);

        // Then — should return error, NOT throw
        assertNotNull(result);
        assertTrue(result.has("error"));
        assertTrue(result.get("error").asBoolean());
        assertTrue(result.get("message").asText().contains("permission"));

        // Read service should NOT be called when permission is denied
        verify(clientReadPlatformService, never()).retrieveOne(anyLong());
    }

    @Test
    @DisplayName("getClientDetailsById returns error when client not found")
    void getClientDetailsById_handlesClientNotFound() {
        // Given
        Long clientId = 999L;
        when(clientReadPlatformService.retrieveOne(clientId))
                .thenThrow(new RuntimeException("Client not found with id: 999"));

        // When
        JsonNode result = adapter.getClientDetailsById(clientId);

        // Then
        assertNotNull(result);
        assertTrue(result.has("error"));
        assertTrue(result.get("error").asBoolean());
        assertEquals("getClientDetailsById", result.get("context").asText());
    }

    @Test
    @DisplayName("getClientDetailsById does not leak stack traces in error response")
    void getClientDetailsById_doesNotLeakStackTraces() {
        // Given
        Long clientId = 1L;
        RuntimeException internalError = new TestDomainRuleException(
                "JDBC connection failed: password=secret123 host=db.internal");
        when(clientReadPlatformService.retrieveOne(clientId)).thenThrow(internalError);

        // When
        JsonNode result = adapter.getClientDetailsById(clientId);

        // Then — error message should be sanitized
        assertNotNull(result);
        assertTrue(result.has("error"));
        String message = result.get("message").asText();
        assertFalse(message.contains("secret123"), "Error message should not contain secrets");
    }

    @Test
    @DisplayName("getClientDetailsById returns standard error response when clientId is null")
    void getClientDetailsById_handlesNullClientId() {
        // When
        JsonNode result = adapter.getClientDetailsById(null);

        // Then
        assertNotNull(result);
        assertTrue(result.has("error"));
        assertEquals("Client ID is required", result.get("message").asText());

        // Verify no services were queried
        verify(securityContext, never()).validateAccessRights(anyString());
        verify(clientReadPlatformService, never()).retrieveOne(any());
    }

    private static class TestAccessDeniedException extends RuntimeException {
        public TestAccessDeniedException(String message) {
            super(message);
        }
    }

    private static class TestDomainRuleException extends RuntimeException {
        public TestDomainRuleException(String message) {
            super(message);
        }
    }
}
