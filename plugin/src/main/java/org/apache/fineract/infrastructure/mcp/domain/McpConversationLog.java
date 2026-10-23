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

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "response", columnDefinition = "TEXT")
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
