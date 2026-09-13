/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Per-request security context: the officer's own Fineract credential, forwarded verbatim.
 *
 * <p>The gateway owns no Fineract service account (ADR-001 §2.1). Every tool call carries this
 * context's {@code authorizationHeader} + {@code tenantId}, so Fineract RBAC evaluates and the
 * audit trail records the real user. The credential is never logged and never sent to the LLM.
 */
public record CallContext(String authorizationHeader, String tenantId, String correlationId,
        java.util.Map<String, Object> session) {

    /**
     * Facts the officer's session knows and the model must not choose: which office they
     * belong to, which staff record is theirs. A body template reads these as
     * {@code ${session.officeId}}, so a branch can never be picked by a sentence.
     */
    public CallContext {
        session = session == null ? java.util.Map.of() : java.util.Map.copyOf(session);
    }

    public CallContext(String authorizationHeader, String tenantId, String correlationId) {
        this(authorizationHeader, tenantId, correlationId, java.util.Map.of());
    }

    public boolean hasCredential() {
        return authorizationHeader != null && !authorizationHeader.isBlank();
    }

    /**
     * Stable digest binding pending approvals to the same user + tenant: the officer who approves
     * must be the officer who asked (ADR-001 §04). Raw credentials are never stored, only this hash.
     */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((authorizationHeader == null ? "" : authorizationHeader).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((tenantId == null ? "" : tenantId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
