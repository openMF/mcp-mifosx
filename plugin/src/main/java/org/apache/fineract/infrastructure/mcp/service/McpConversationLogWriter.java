package org.apache.fineract.infrastructure.mcp.service;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.mcp.domain.McpConversationLog;
import org.apache.fineract.infrastructure.mcp.domain.McpConversationLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one turn, and makes it succeed or fail here rather than somewhere nobody is listening.
 *
 * <p>This class exists for three reasons, and each one is a bug that was in the code before it.
 *
 * <p><b>Why it is a separate bean.</b> Spring applies {@code @Transactional} through a proxy. A
 * method that calls another method on {@code this} runs the code directly and never passes
 * through that proxy, so the annotation below would be read by nobody. {@link
 * McpConversationLogService} has to call across a bean boundary for the propagation to mean
 * anything at all.
 *
 * <p><b>Why REQUIRES_NEW.</b> The record belongs to the institution, not to whoever happened to
 * trigger it. Without its own transaction a failed write marks the caller's transaction
 * rollback-only, so the caller's own commit then throws and the careful "it did not store"
 * answer is built and immediately discarded. Its own transaction also means a caller that rolls
 * back later cannot erase a turn that really happened.
 *
 * <p><b>Why saveAndFlush and not save.</b> Fineract runs on EclipseLink, which holds changes in
 * memory and writes them when the transaction commits. Under a plain {@code save} the INSERT
 * runs after the calling method has already returned, so a value too long for its column, a
 * constraint violation or a dropped connection could never reach the code that wanted to report
 * it. Flushing forces the statement to run while someone is still there to catch it.
 */
@Component
@RequiredArgsConstructor
public class McpConversationLogWriter {

    private final McpConversationLogRepository repository;

    /**
     * Store one turn and make sure it has really reached the database before returning.
     *
     * @throws org.springframework.dao.DataAccessException if the row could not be written, raised
     *                                                     here rather than at some later commit
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public McpConversationLog write(final McpConversationLog turn) {
        return repository.saveAndFlush(turn);
    }
}
