package org.apache.fineract.infrastructure.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.apache.fineract.infrastructure.mcp.domain.McpConversationLog;
import org.apache.fineract.infrastructure.mcp.domain.McpConversationLogRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the conversation record has to get right.
 *
 * <p>Three of these matter more than the rest. A turn filed under the wrong officer is worse than
 * no record. A turn readable by the wrong officer is a leak. And a turn that fails to store
 * without anyone being told leaves a record that looks complete and is not.
 *
 * <p>That third one is the reason for the transaction tests near the bottom. An earlier version
 * of this class passed every behavioural test here while being unable to report a storage failure
 * at all, because a mock repository throws the instant it is called and a real one does not. Those
 * tests check the shape of the code rather than its behaviour, which is a poor substitute for an
 * integration test, and is here because it is the only thing a mock can honestly guard.
 */
@ExtendWith(MockitoExtension.class)
public class McpConversationLogServiceTest {

    @Mock
    private McpConversationLogRepository repository;

    @Mock
    private McpConversationLogWriter writer;

    @Mock
    private McpAuthenticationService mcpAuthenticationService;

    @Mock
    private AppUser authenticatedUser;

    @InjectMocks
    private McpConversationLogService service;

    private void signedInAsPriya() {
        when(mcpAuthenticationService.getAuthenticatedUser()).thenReturn(authenticatedUser);
        when(authenticatedUser.getId()).thenReturn(12L);
    }

    private void storesWhateverItIsGiven() {
        when(writer.write(any(McpConversationLog.class))).thenAnswer(call -> call.getArgument(0));
    }

    private McpConversationLog stored() {
        final ArgumentCaptor<McpConversationLog> captor = ArgumentCaptor.forClass(McpConversationLog.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
    }

    // ── identity ────────────────────────────────────────────────────────────

    /** The officer comes from the authenticated principal, never from the caller. */
    @Test
    public void recordsTheTurnAgainstTheAuthenticatedOfficer() {
        signedInAsPriya();
        when(authenticatedUser.getUsername()).thenReturn("priya");
        storesWhateverItIsGiven();

        service.record("conv-1", "find the client aisha", "Aisha Bello has one active loan.", "qwen/qwen3.6-27b",
                "groq", 1L);

        assertThat(stored().getUserId()).isEqualTo(12L);
        assertThat(stored().getUsername()).isEqualTo("priya");
        assertThat(stored().getCreatedAt()).isNotNull();
    }

    /** No signed-in user must not mean a turn recorded against nobody in particular. */
    @Test
    public void recordsWithNoUserWhenThereIsNoAuthenticatedPrincipal() {
        when(mcpAuthenticationService.getAuthenticatedUser()).thenReturn(null);
        storesWhateverItIsGiven();

        service.record("conv-1", "find aisha", "...", "m", "p", null);

        assertThat(stored().getUserId()).isNull();
        assertThat(stored().getUsername()).isNull();
    }

    // ── the two columns the ticket exists for ───────────────────────────────

    /**
     * The whole point of the model and provider columns. An institution has to be able to say
     * which provider saw a client's details, so neither may be dropped on the way in.
     */
    @Test
    public void keepsTheModelAndProviderExactlyAsGiven() {
        signedInAsPriya();
        storesWhateverItIsGiven();

        service.record("conv-2", "show client 1", "...", "llama-3.1-8b", "ollama", null);

        assertThat(stored().getModel()).isEqualTo("llama-3.1-8b");
        assertThat(stored().getProvider()).isEqualTo("ollama");
    }

    @Test
    public void keepsThePromptAndResponseExactlyAsGiven() {
        signedInAsPriya();
        storesWhateverItIsGiven();

        service.record("conv-2", "what is the repayment schedule", "Six instalments, settling 28 February 2027.",
                "m", "p", 7L);

        assertThat(stored().getPrompt()).isEqualTo("what is the repayment schedule");
        assertThat(stored().getResponse()).isEqualTo("Six instalments, settling 28 February 2027.");
        assertThat(stored().getClientId()).isEqualTo(7L);
    }

    // ── recording never reads before it writes ──────────────────────────────

    /**
     * The bug this design exists to avoid.
     *
     * <p>An earlier version derived a position as max(sequence_no) + 1 before inserting. Two
     * turns recorded at the same moment read the same maximum, computed the same position, and
     * the second was rejected by a unique constraint and silently dropped. Order now comes from
     * the database's own id, so recording performs no read at all and nothing can collide.
     */
    @Test
    public void recordingReadsNothingBeforeItWrites() {
        signedInAsPriya();
        storesWhateverItIsGiven();

        service.record("conv-3", "and the schedule?", "...", "m", "p", null);

        verify(repository, never()).findByConversationIdAndUserIdOrderByIdAsc(anyString(), anyLong());
        verify(repository, never()).findByUserIdOrderByIdDesc(anyLong());
    }

    // ── failure is reported, never swallowed ────────────────────────────────

    /** The officer keeps their answer even when the record cannot be written. */
    @Test
    public void aStorageFailureDoesNotReachTheCallerAsAnException() {
        signedInAsPriya();
        when(writer.write(any(McpConversationLog.class))).thenThrow(new RuntimeException("database is down"));

        final McpConversationLogService.RecordOutcome outcome = service.record("conv-5", "find aisha", "...", "m", "p",
                null);

        assertThat(outcome.recorded()).isFalse();
    }

    /**
     * But the caller is told. A record that can quietly lose entries is worse than no record,
     * because it invites confidence it has not earned.
     */
    @Test
    public void aStorageFailureIsReportedRatherThanSilent() {
        signedInAsPriya();
        when(writer.write(any(McpConversationLog.class))).thenThrow(new IllegalStateException("connection reset"));

        final McpConversationLogService.RecordOutcome outcome = service.record("conv-5", "find aisha", "...", "m", "p",
                null);

        assertThat(outcome.recorded()).isFalse();
        assertThat(outcome.failureReason()).isEqualTo("IllegalStateException");
        assertThat(outcome.turn()).isNull();
    }

    @Test
    public void aStoredTurnIsReportedAsStored() {
        signedInAsPriya();
        storesWhateverItIsGiven();

        final McpConversationLogService.RecordOutcome outcome = service.record("conv-6", "hello", "...", "m", "p",
                null);

        assertThat(outcome.recorded()).isTrue();
        assertThat(outcome.turn()).isNotNull();
        assertThat(outcome.failureReason()).isNull();
    }

    // ── the failure report has to survive the commit ────────────────────────

    /**
     * The regression that the behavioural tests above cannot see.
     *
     * <p>{@code record} used to be annotated {@code @Transactional}. Its transaction committed
     * after the method returned, so a failed write marked that transaction rollback-only, the
     * catch block built its careful {@code recorded=false}, and the commit then threw and
     * discarded it. The caller got a generic error and could not tell a full disk from a denied
     * permission. Every behavioural test above still passed, because a mock throws immediately
     * and a real database does not.
     *
     * <p>Writing is delegated to {@link McpConversationLogWriter}, which has its own transaction.
     * Putting {@code @Transactional} back here reinstates the bug, so this fails if anyone does.
     */
    @Test
    public void recordingDoesNotOpenATransactionThatOutlivesItsOwnErrorHandling() throws NoSuchMethodException {
        final Method record = McpConversationLogService.class.getMethod("record", String.class, String.class,
                String.class, String.class, String.class, Long.class);

        assertThat(record.isAnnotationPresent(Transactional.class))
                .as("record() must not open a transaction that commits after its catch block has run")
                .isFalse();
        assertThat(record.isAnnotationPresent(jakarta.transaction.Transactional.class))
                .as("record() must not open a transaction that commits after its catch block has run")
                .isFalse();
    }

    /** Reads may be transactional. Only the write path had the problem. */
    @Test
    public void readingIsStillTransactionalAndReadOnly() throws NoSuchMethodException {
        final Transactional onRead = McpConversationLogService.class.getMethod("myTurnsOf", String.class)
                .getAnnotation(Transactional.class);

        assertThat(onRead).isNotNull();
        assertThat(onRead.readOnly()).isTrue();
    }

    // ── a caller mistake is raised, not reported ────────────────────────────

    @Test
    public void refusesATurnWithNothingToGroupItBy() {
        assertThatThrownBy(() -> service.record("  ", "find aisha", "...", "m", "p", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(writer, never()).write(any());
    }

    @Test
    public void refusesATurnWithNoPrompt() {
        assertThatThrownBy(() -> service.record("conv-7", "", "...", "m", "p", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(writer, never()).write(any());
    }

    // ── values the column cannot hold are refused, not truncated ────────────

    /**
     * The failure this prevents is specific to MySQL outside strict mode, which truncates rather
     * than rejecting. A conversation id cut to 100 characters no longer matches the id the caller
     * holds, so the turn is stored and can never be read back, and two conversations sharing a
     * prefix merge into one. Refusing the call says so immediately instead.
     */
    @Test
    public void refusesAConversationIdLongerThanItsColumn() {
        assertThatThrownBy(() -> service.record("c".repeat(101), "find aisha", "...", "m", "p", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conversationId");
        verify(writer, never()).write(any());
    }

    @Test
    public void refusesAModelLongerThanItsColumn() {
        assertThatThrownBy(() -> service.record("conv-9", "find aisha", "...", "m".repeat(151), "p", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model");
        verify(writer, never()).write(any());
    }

    @Test
    public void refusesAProviderLongerThanItsColumn() {
        assertThatThrownBy(() -> service.record("conv-9", "find aisha", "...", "m", "p".repeat(101), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provider");
        verify(writer, never()).write(any());
    }

    /** The boundary itself is allowed. Off by one here would reject perfectly storable turns. */
    @Test
    public void acceptsValuesExactlyAtTheLimit() {
        signedInAsPriya();
        storesWhateverItIsGiven();

        final McpConversationLogService.RecordOutcome outcome = service.record("c".repeat(100), "find aisha", "...",
                "m".repeat(150), "p".repeat(100), null);

        assertThat(outcome.recorded()).isTrue();
    }

    /**
     * The column counts characters; {@code String.length()} counts UTF-16 units. Measuring the
     * wrong one would reject a hundred emoji, or a name in a script outside the basic plane, that
     * the database would have stored without complaint.
     */
    @Test
    public void countsCharactersRatherThanUtf16Units() {
        signedInAsPriya();
        storesWhateverItIsGiven();

        final String hundredEmoji = "😀".repeat(100);
        assertThat(hundredEmoji.length()).isEqualTo(200);
        assertThat(hundredEmoji.codePointCount(0, hundredEmoji.length())).isEqualTo(100);

        assertThat(service.record(hundredEmoji, "find aisha", "...", "m", "p", null).recorded()).isTrue();
    }

    /** A missing model or provider is normal, and must not be mistaken for an over-length one. */
    @Test
    public void allowsAMissingModelAndProvider() {
        signedInAsPriya();
        storesWhateverItIsGiven();

        assertThat(service.record("conv-9", "find aisha", "...", null, null, null).recorded()).isTrue();
    }

    // ── reading is scoped to the officer who asks ───────────────────────────

    /**
     * Knowing a conversation id is not a right to read that conversation. Without the user in
     * the query, any officer could read any other officer's transcripts.
     */
    @Test
    public void readingAConversationIsScopedToTheAuthenticatedOfficer() {
        signedInAsPriya();
        when(repository.findByConversationIdAndUserIdOrderByIdAsc("conv-8", 12L)).thenReturn(List.of());

        service.myTurnsOf("conv-8");

        verify(repository).findByConversationIdAndUserIdOrderByIdAsc("conv-8", 12L);
    }

    @Test
    public void readingAConversationWithNoAuthenticatedUserReturnsNothing() {
        when(mcpAuthenticationService.getAuthenticatedUser()).thenReturn(null);

        assertThat(service.myTurnsOf("conv-8")).isEmpty();
        verify(repository, never()).findByConversationIdAndUserIdOrderByIdAsc(anyString(), anyLong());
    }

    @Test
    public void readingMyTurnsUsesTheAuthenticatedOfficer() {
        signedInAsPriya();

        service.myTurns();

        verify(repository).findByUserIdOrderByIdDesc(12L);
    }

    @Test
    public void readingMyTurnsWithNoAuthenticatedUserReturnsNothing() {
        when(mcpAuthenticationService.getAuthenticatedUser()).thenReturn(null);

        assertThat(service.myTurns()).isEmpty();
        verify(repository, never()).findByUserIdOrderByIdDesc(anyLong());
    }

    @Test
    public void aBlankConversationIdReadsNothingRatherThanEverything() {
        assertThat(service.myTurnsOf("  ")).isEmpty();
        verify(repository, never()).findByConversationIdAndUserIdOrderByIdAsc(anyString(), anyLong());
    }
}
