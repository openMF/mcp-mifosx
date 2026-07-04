package org.apache.fineract.infrastructure.mcp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.AppUser;
import org.apache.fineract.infrastructure.security.service.SpringSecurityPlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Service for handling authentication and authorization in MCP tool calls.
 *
 * <p>This service ensures that MCP tool executions run within the proper
 * security context of the authenticated user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpAuthenticationService {

    private final SpringSecurityPlatformSecurityContext springSecurityPlatformSecurityContext;
    private final AppUserRepository appUserRepository;

    /**
     * Gets the currently authenticated user for MCP tool execution.
     *
     * @return The authenticated AppUser, or null if no authentication is present
     */
    public AppUser getAuthenticatedUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("MCP tool called without authentication");
            return null;
        }

        String username = authentication.getName();

        return appUserRepository.findAppUserByName(username)
                .orElse(null);
    }

    /**
     * Verifies that the current user has permission to execute MCP tools.
     *
     * @return true if the user is authenticated and authorized
     */
    public boolean isAuthorized() {
        if (!springSecurityPlatformSecurityContext.doesCurrentUserHavePermission()) {
            log.warn("Current user does not have permission for MCP operations");
            return false;
        }
        return true;
    }
}