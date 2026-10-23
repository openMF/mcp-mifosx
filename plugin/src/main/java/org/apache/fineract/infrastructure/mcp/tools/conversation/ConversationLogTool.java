package org.apache.fineract.infrastructure.mcp.tools.conversation;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.mcp.domain.McpConversationLog;
import org.apache.fineract.infrastructure.mcp.service.McpAuthenticationService;
import org.apache.fineract.infrastructure.mcp.service.McpConversationLogService;
import org.apache.fineract.infrastructure.mcp.service.McpErrorSanitizer;
import org.apache.fineract.infrastructure.mcp.tools.FineractMcpTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Records what the assistant was asked and what it answered.
 *
 * <p><b>Why this is a tool rather than a REST endpoint.</b> The conversation happens in the
 * client, not here. An MCP server only ever sees tool calls: it never sees the officer's
 * question, the assistant's answer, or which model produced it, because the protocol does not
 * carry them. The {@code clientInfo} exchanged when a session opens names the client application
 * and its version, nothing more. So the facts have to be sent deliberately, and the channel the
 * client is already connected on is the obvious way to send them.
 *
 * <p>The calling application is expected to invoke this itself once a turn completes, rather than
 * leaving it to the model to decide. A record written only when a model chooses to write it is
 * not a record.
 *
 * <p>The officer is not a parameter. It is resolved from the authenticated Fineract principal, so
 * a caller cannot file a turn under somebody else's name, and cannot read somebody else's turns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationLogTool implements FineractMcpTool {

    private final McpConversationLogService conversationLogService;
    private final McpErrorSanitizer mcpErrorSanitizer;
    private final McpAuthenticationService mcpAuthenticationService;

    @Override
    public String getCategory() {
        return "conversation";
    }

    /**
     * The result of recording a turn.
     *
     * <p>{@code recorded} is false when the turn could not be stored. The conversation itself was
     * unaffected, but the record is incomplete, and the caller is told so rather than left to
     * assume it worked.
     */
    public record RecordResult(boolean recorded, Long id, String conversationId, String username, String model,
            String provider, String createdAt, String failureReason) {}

    /** One recorded turn, as read back. */
    public record RecordedTurn(Long id, String conversationId, String username, String prompt, String response,
            String model, String provider, Long clientId, String createdAt) {

        static RecordedTurn from(final McpConversationLog log) {
            return new RecordedTurn(log.getId(), log.getConversationId(), log.getUsername(), log.getPrompt(),
                    log.getResponse(), log.getModel(), log.getProvider(), log.getClientId(),
                    log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
        }
    }

    @Tool(name = "fineract_conversation_record",
          description = "Record one completed turn of an assistant conversation against the signed-in user: "
              + "the prompt, the response, and which model and AI provider produced it. Intended to be called "
              + "by the client application once a turn finishes, so the institution has a record of what its "
              + "assistant was asked, what it answered, and whose infrastructure saw the data.")
    public RecordResult recordTurn(
            @ToolParam(description = "Identifier grouping the turns of one conversation together")
            String conversationId,
            @ToolParam(description = "What the user asked")
            String prompt,
            @ToolParam(description = "What the assistant answered", required = false)
            String response,
            @ToolParam(description = "The model that produced the answer, for example qwen/qwen3.6-27b",
                       required = false)
            String model,
            @ToolParam(description = "The AI provider that served the model, for example groq, openai or ollama",
                       required = false)
            String provider,
            @ToolParam(description = "The client this turn was about, when it was about one", required = false)
            Long clientId) {

        log.debug("MCP Tool: recording a turn of conversation '{}'", conversationId);

        if (!mcpAuthenticationService.isAuthorized()) {
            throw new SecurityException(
                    "User is not authorized to execute MCP tools. Required permissions: ALL_FUNCTIONS or USE_MCP_TOOLS");
        }

        try {
            final McpConversationLogService.RecordOutcome outcome = conversationLogService.record(conversationId,
                    prompt, response, model, provider, clientId);

            if (!outcome.recorded()) {
                return new RecordResult(false, null, conversationId, null, model, provider, null,
                        outcome.failureReason());
            }

            final McpConversationLog turn = outcome.turn();
            return new RecordResult(true, turn.getId(), turn.getConversationId(), turn.getUsername(), turn.getModel(),
                    turn.getProvider(), turn.getCreatedAt() == null ? null : turn.getCreatedAt().toString(), null);
        } catch (Exception e) {
            throw new RuntimeException(mcpErrorSanitizer.sanitize(e, "fineract_conversation_record"));
        }
    }

    @Tool(name = "fineract_conversation_history",
          description = "Return the signed-in user's own recorded turns of one conversation, oldest first.")
    public List<RecordedTurn> conversationHistory(
            @ToolParam(description = "Identifier of the conversation to read")
            String conversationId) {

        if (!mcpAuthenticationService.isAuthorized()) {
            throw new SecurityException(
                    "User is not authorized to execute MCP tools. Required permissions: ALL_FUNCTIONS or USE_MCP_TOOLS");
        }

        try {
            return conversationLogService.myTurnsOf(conversationId).stream().map(RecordedTurn::from).toList();
        } catch (Exception e) {
            throw new RuntimeException(mcpErrorSanitizer.sanitize(e, "fineract_conversation_history"));
        }
    }
}
