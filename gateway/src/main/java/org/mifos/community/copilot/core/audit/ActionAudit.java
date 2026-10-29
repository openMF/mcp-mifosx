/** Copyright since 2026 Mifos Initiative. Mozilla Public License, v. 2.0. */
package org.mifos.community.copilot.core.audit;

import java.util.Optional;

/** Internal persistence boundary for the single approved MCP write. No payloads or credentials. */
public interface ActionAudit {
    enum State { proposed, approved, rejected, expired, executing, succeeded, failed }

    record Entry(String correlationId, String tool, String tenant, String callerFingerprint,
            String sessionReference, State state, String createdAt, String updatedAt,
            Integer httpStatus, String failureClass) {}

    /** Only typed, allow-listed metadata may cross the Go result boundary. */
    record Outcome(boolean success, int httpStatus, String failureClass) {
        public Outcome {
            if (httpStatus != 0 && (httpStatus < 100 || httpStatus > 599)
                    || success && (httpStatus < 200 || httpStatus >= 300 || failureClass != null)
                    || !success && !java.util.Set.of("http_error", "transport_error", "response_error",
                            "invalid_claim", "execution_blocked").contains(
                                    failureClass == null ? "" : failureClass)) {
                throw new IllegalArgumentException("Invalid audit outcome");
            }
        }
    }

    void create(Entry entry);
    void transition(String correlationId, State state, Outcome outcome);
    Optional<Entry> find(String correlationId);
}
