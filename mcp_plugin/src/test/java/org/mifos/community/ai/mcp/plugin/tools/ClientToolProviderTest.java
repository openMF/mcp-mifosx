// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mifos.community.ai.mcp.plugin.service.ClientServiceAdapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ClientToolProvider}.
 * <p>
 * Verifies that the MCP tool correctly delegates to the
 * {@link ClientServiceAdapter} and passes arguments through.
 */
@ExtendWith(MockitoExtension.class)
class ClientToolProviderTest {

    @Mock
    private ClientServiceAdapter clientServiceAdapter;

    private ClientToolProvider clientToolProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        clientToolProvider = new ClientToolProvider(clientServiceAdapter);
    }

    @Test
    @DisplayName("getClientDetailsById delegates to ClientServiceAdapter with correct clientId")
    void getClientDetailsById_delegatesToServiceAdapter() {
        // Given
        Integer clientId = 42;
        ObjectNode expectedResponse = objectMapper.createObjectNode();
        expectedResponse.put("id", 42);
        expectedResponse.put("displayName", "John Doe");
        expectedResponse.put("accountNo", "00000042");
        when(clientServiceAdapter.getClientDetailsById(42L)).thenReturn(expectedResponse);

        // When
        JsonNode result = clientToolProvider.getClientDetailsById(clientId);

        // Then
        verify(clientServiceAdapter, times(1)).getClientDetailsById(42L);
        assertNotNull(result);
        assertEquals(42, result.get("id").asInt());
        assertEquals("John Doe", result.get("displayName").asText());
        assertEquals("00000042", result.get("accountNo").asText());
    }

    @Test
    @DisplayName("getClientDetailsById converts Integer clientId to Long for adapter")
    void getClientDetailsById_convertsIntegerToLong() {
        // Given
        Integer clientId = 1;
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", 1);
        when(clientServiceAdapter.getClientDetailsById(1L)).thenReturn(response);

        // When
        clientToolProvider.getClientDetailsById(clientId);

        // Then — verify the adapter receives a Long, not Integer
        verify(clientServiceAdapter).getClientDetailsById(1L);
    }

    @Test
    @DisplayName("getClientDetailsById returns error response when adapter fails")
    void getClientDetailsById_returnsErrorOnFailure() {
        // Given
        Integer clientId = 999;
        ObjectNode errorResponse = objectMapper.createObjectNode();
        errorResponse.put("error", true);
        errorResponse.put("message", "Client not found");
        when(clientServiceAdapter.getClientDetailsById(999L)).thenReturn(errorResponse);

        // When
        JsonNode result = clientToolProvider.getClientDetailsById(clientId);

        // Then
        assertNotNull(result);
        assertTrue(result.has("error"));
        assertTrue(result.get("error").asBoolean());
    }

    @Test
    @DisplayName("getClientDetailsById passes null directly to adapter without throwing NPE")
    void getClientDetailsById_handlesNullClientId() {
        // Given
        ObjectNode errorResponse = objectMapper.createObjectNode();
        errorResponse.put("error", true);
        errorResponse.put("message", "Client ID is required");
        when(clientServiceAdapter.getClientDetailsById(null)).thenReturn(errorResponse);

        // When
        JsonNode result = clientToolProvider.getClientDetailsById(null);

        // Then
        verify(clientServiceAdapter).getClientDetailsById(null);
        assertNotNull(result);
        assertTrue(result.has("error"));
        assertEquals("Client ID is required", result.get("message").asText());
    }
}
