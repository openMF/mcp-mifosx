package org.apache.fineract.infrastructure.mcp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One turn of a conversation with the assistant, as the institution's record of it.
 *
 * <p>This is not an officer's chat history. It is what a supervisor or an auditor needs months
 * afterwards: what the assistant was asked, what it answered, and which model on whose
 * infrastructure produced that answer. {@code mcp_tool_execution_log} records the calls a tool
 * made, and cannot say which conversation they came from or what the officer was told.
 *
 * <p>The user is never taken from the caller. It is resolved from Fineract's authenticated
 * principal, so a client cannot file a turn under another officer's name.
 *
 * <p>There is no position column. Order within a conversation comes from {@link #id}, which is
 * already monotonic. See the changelog for why a derived position was removed.
 */
@Entity
@Table(name = "mcp_conversation_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpConversationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Groups the turns of one conversation.
     *
     * <p>Unused by the recording itself, and present because it cannot be added later: once rows
     * exist without it, nothing in the data says which turns belonged together.
     */
    @Column(name = "conversation_id", nullable = false, length = 100)
    private String conversationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", length = 100)
    private String username;

    /**
     * No {@code columnDefinition} and no {@code @Lob} on either of these.
     *
     * <p>{@code columnDefinition} only ever fed schema generation, which this plugin does not use:
     * Liquibase owns the table, and the changelog declares these as CLOB. Leaving the attribute
     * behind would have described a column type that is not what MySQL actually gets.
     *
     * <p>{@code @Lob} was considered and left out deliberately. The column type is already settled
     * by the migration, and the annotation would only change how the value is bound, moving it
     * onto EclipseLink's large-object path. That path behaves differently across PostgreSQL and
     * MySQL, and there is no integration test here against either, so it would be a change made on
     * faith. A plain String binds through setString and reads back through getString against a
     * CLOB, TEXT or LONGTEXT column on all three supported databases.
     */
    @Column(name = "prompt", nullable = false)
    private String prompt;

    @Column(name = "response")
    private String response;

    /** The model that produced the answer, for example qwen/qwen3.6-27b. */
    @Column(name = "model", length = 150)
    private String model;

    /** Whose infrastructure served it, for example groq, openai or ollama. */
    @Column(name = "provider", length = 100)
    private String provider;

    /** The borrower under discussion, when the turn had one. */
    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
