/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>In-memory case store. Swap for Redis / Postgres for multi-instance deploys.
 */
package org.community.mifos.agentic.loan.service;

import org.community.mifos.agentic.loan.config.LoanProperties;
import org.community.mifos.agentic.loan.model.LoanCaseState;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoanCaseStore {

    private final ConcurrentHashMap<String, LoanCaseState> cases = new ConcurrentHashMap<>();
    private final Duration ttl;

    public LoanCaseStore(LoanProperties props) {
        this.ttl = Duration.ofHours(props.getStateTtlHours());
    }

    public void put(LoanCaseState state) {
        cases.put(state.getWorkflowId(), state);
    }

    public Optional<LoanCaseState> get(String workflowId) {
        LoanCaseState s = cases.get(workflowId);
        if (s == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(s.getUpdatedAt().plus(ttl))) {
            cases.remove(workflowId);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public Collection<LoanCaseState> all() {
        return cases.values();
    }

    public void remove(String workflowId) {
        cases.remove(workflowId);
    }
}
