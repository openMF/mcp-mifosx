package org.apache.fineract.infrastructure.mcp.service;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.mcp.domain.McpConversationLog;
import org.apache.fineract.infrastructure.mcp.domain.McpConversationLogRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what the assistant was asked and what it answered.
 *
 * <p>Three rules hold here, and all three follow from this being the institution's record rather
 * than the officer's notes.
 *
 * <p><b>The officer is never taken from the caller.</b> It is resolved from Fineract's
 * authenticated principal, so a client cannot file a turn under somebody else's name by changing
 * a field.
 *
 * <p><b>A failure to record never takes away the officer's answer.</b> The conversation it
 * describes has already happened, and failing the caller would turn a storage problem into a
 * broken assistant.
 *
 * <p><b>But a failure is never silent either.</b> It is returned as an outcome the caller can
 * see and act on, not swallowed into a log line. A record that can quietly lose entries is worse
 * than no record, because it invites confidence it has not earned.
 *
 * <p>Making that third rule true is the job of {@link McpConversationLogWriter}, and the reason
 * writing is not done here. Catching an exception around a write only reports anything if the
 * write has actually happened by the time the catch runs, which under EclipseLink and an
 * enclosing transaction it has not. The writer's javadoc gives the whole story.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpConversationLogService {

    private final McpConversationLogRepository repository;
    private final McpConversationLogWriter writer;
    private final McpAuthenticationService mcpAuthenticationService;

    /**
     * The result of trying to record a turn.
     *
     * <p>A separate type rather than a nullable entity, so that "it did not store" is something
     * the caller has to look at rather than something it can miss.
     */
    public record RecordOutcome(boolean recorded, McpConversationLog turn, String failureReason) {

        static RecordOutcome stored(final McpConversationLog turn) {
            return new RecordOutcome(true, turn, null);
        }

        static RecordOutcome failed(final String reason) {
            return new RecordOutcome(false, null, reason);
        }
    }

    /**
     * Record one turn.
     *
     * @param conversationId groups turns that belong together, supplied by the caller because only
     *                       the caller knows where one conversation ends and the next begins
     * @param clientId       the borrower under discussion, or null when the turn had none
     * @throws IllegalArgumentException when the caller omits something required. That is a mistake
     *                                  in the call rather than a storage problem, so it is raised
     *                                  rather than reported as an outcome.
     */
    public RecordOutcome record(final String conversationId, final String prompt, final String response,
            final String model, final String provider, final Long clientId) {

        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required and must not be blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required and must not be blank");
        }
        // Refused here rather than left to the database. PostgreSQL rejects an over-length value,
        // but MySQL outside strict mode truncates it and carries on, which is the worse outcome:
        // a conversation id cut to 100 characters no longer matches the id the caller holds, so
        // the turn is stored and can never be read back, and two conversations that share a
        // prefix quietly merge into one.
        requireAtMost("conversationId", conversationId, 100);
        requireAtMost("model", model, 150);
        requireAtMost("provider", provider, 100);

        // Deliberately not @Transactional. A transaction opened here would commit after this
        // method returns, which is after the catch below has run: a failed write would mark the
        // transaction rollback-only, the catch would build its tidy "not stored" answer, and the
        // commit would then throw and throw that answer away. The write has its own transaction
        // in McpConversationLogWriter instead, and finishes inside the try.
        try {
            final AppUser user = mcpAuthenticationService.getAuthenticatedUser();

            final McpConversationLog turn = McpConversationLog.builder().conversationId(conversationId)
                    .userId(user == null ? null : user.getId()).username(user == null ? null : user.getUsername())
                    .prompt(prompt).response(response).model(model).provider(provider).clientId(clientId)
                    .createdAt(OffsetDateTime.now()).build();

            return RecordOutcome.stored(writer.write(turn));
        } catch (RuntimeException e) {
            // Reported, not swallowed. The caller keeps its answer and also learns the record is
            // incomplete, which is the only way anyone can act on it.
            //
            // Only the exception type goes to ERROR. A persistence exception can carry the failed
            // statement and its bound values, and those values are the officer's question and the
            // borrower's details, so putting the whole thing in a log file moves the data somewhere
            // nobody scoped it to go. The trace is still available at DEBUG, which is the pattern
            // McpErrorSanitizer already uses.
            log.error("Could not record a turn of conversation {}. Failed with {}. The conversation itself "
                    + "was unaffected.", conversationId, e.getClass().getSimpleName());
            log.debug("Full exception trace for conversation {}", conversationId, e);
            return RecordOutcome.failed(e.getClass().getSimpleName());
        }
    }

    /**
     * Refuse a value the column cannot hold, before the database has to decide what to do with it.
     *
     * <p>Counted in code points rather than {@code String.length()}, which counts UTF-16 units. A
     * name in a script outside the basic plane, or a single emoji, is one character to the column
     * and two to Java, and rejecting a value the database would have accepted is its own bug.
     */
    private static void requireAtMost(final String field, final String value, final int maxCharacters) {
        if (value != null && value.codePointCount(0, value.length()) > maxCharacters) {
            throw new IllegalArgumentException(field + " must not exceed " + maxCharacters + " characters");
        }
    }

    /**
     * The signed-in officer's own turns of one conversation, oldest first.
     *
     * <p>Scoped to the authenticated user as well as the conversation. Knowing a conversation id
     * is not a right to read that conversation: an institution that wants a supervisor to read
     * another officer's transcripts should grant that through Fineract reporting, deliberately,
     * rather than through a tool every client already holds.
     */
    @Transactional(readOnly = true)
    public List<McpConversationLog> myTurnsOf(final String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        final AppUser user = mcpAuthenticationService.getAuthenticatedUser();
        if (user == null) {
            return List.of();
        }
        return repository.findByConversationIdAndUserIdOrderByIdAsc(conversationId, user.getId());
    }

    /**
     * The signed-in officer's own turns, newest first.
     *
     * <p>Scoped to the authenticated user rather than to an id passed in, so it cannot be pointed
     * at another officer's conversations.
     */
    @Transactional(readOnly = true)
    public List<McpConversationLog> myTurns() {
        final AppUser user = mcpAuthenticationService.getAuthenticatedUser();
        if (user == null) {
            return List.of();
        }
        return repository.findByUserIdOrderByIdDesc(user.getId());
    }
}
