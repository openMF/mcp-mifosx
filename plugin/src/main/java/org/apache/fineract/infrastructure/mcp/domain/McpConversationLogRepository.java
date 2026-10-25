package org.apache.fineract.infrastructure.mcp.domain;

import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * Reads and writes the conversation record.
 *
 * <p>Ordering is by id rather than by a stored position. The id is assigned by the database on
 * insert, so it already reflects the order turns were recorded, and nothing has to be read and
 * incremented before writing.
 *
 * <p>Every read here is scoped by {@code userId}. A conversation id alone is not a right to read
 * a conversation: reading one belonging to another officer has to go through Fineract reporting,
 * where an institution can grant that deliberately, rather than through a tool any client holds.
 *
 * <p><b>There is no delete, and that is enforced rather than asked for.</b> This extends
 * {@link Repository}, the empty marker interface, and declares the three methods the plugin
 * actually uses. Extending {@code JpaRepository} would have inherited {@code delete},
 * {@code deleteById}, {@code deleteAll} and {@code deleteAllInBatch} into the public API, so a
 * comment saying "please do not delete" would have been the only thing standing between this
 * record and someone removing a turn from it. Spring Data still supplies the implementations;
 * only the methods named here can be called. Adding a delete means editing this file, which is
 * a decision somebody has to make on purpose and a reviewer can see.
 */
public interface McpConversationLogRepository extends Repository<McpConversationLog, Long> {

    /**
     * Store one turn and make it reach the database before returning.
     *
     * <p>{@code saveAndFlush} rather than {@code save} because EclipseLink defers writes to
     * commit, and a write that lands after its caller has returned cannot be reported to anyone.
     * {@link org.apache.fineract.infrastructure.mcp.service.McpConversationLogWriter} explains it
     * in full.
     */
    McpConversationLog saveAndFlush(McpConversationLog turn);

    /** The officer's own turns of one conversation, oldest first. */
    List<McpConversationLog> findByConversationIdAndUserIdOrderByIdAsc(String conversationId, Long userId);

    /** One officer's own turns across every conversation, newest first. */
    List<McpConversationLog> findByUserIdOrderByIdDesc(Long userId);
}
