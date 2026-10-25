package org.apache.fineract.infrastructure.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import org.apache.fineract.infrastructure.mcp.domain.McpConversationLog;
import org.apache.fineract.infrastructure.mcp.domain.McpConversationLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The writer exists so that a failed write fails where someone can still report it.
 *
 * <p>That guarantee rests on two things a mock cannot demonstrate: the write runs in its own
 * transaction, and the statement is flushed rather than left for a later commit. Both are checked
 * here as structure, because the behaviour they produce only appears against a real database.
 */
@ExtendWith(MockitoExtension.class)
public class McpConversationLogWriterTest {

    @Mock
    private McpConversationLogRepository repository;

    @InjectMocks
    private McpConversationLogWriter writer;

    @Test
    public void writesTheTurnAndReturnsWhatWasStored() {
        final McpConversationLog turn = McpConversationLog.builder().conversationId("conv-1").prompt("find aisha")
                .build();
        when(repository.saveAndFlush(turn)).thenReturn(turn);

        assertThat(writer.write(turn)).isSameAs(turn);
        verify(repository).saveAndFlush(turn);
    }

    /**
     * Its own transaction, so a failed write cannot mark the caller's transaction rollback-only.
     *
     * <p>Without this the caller's commit throws after the catch block has already produced its
     * "could not store" answer, and that answer is discarded on the way out.
     */
    @Test
    public void writesInATransactionOfItsOwn() throws NoSuchMethodException {
        final Method write = McpConversationLogWriter.class.getMethod("write", McpConversationLog.class);
        final Transactional annotation = write.getAnnotation(Transactional.class);

        assertThat(annotation).as("the write must carry its own transaction").isNotNull();
        assertThat(annotation.propagation())
                .as("REQUIRES_NEW, so a failed record cannot poison the caller's transaction")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    /**
     * The writer has to be a bean of its own.
     *
     * <p>Spring applies {@code @Transactional} through a proxy. Had this method lived on
     * {@link McpConversationLogService} and been called on {@code this}, the call would bypass
     * the proxy and the annotation above would do nothing at all.
     */
    @Test
    public void isASeparateBeanSoTheAnnotationIsActuallyApplied() {
        assertThat(McpConversationLogWriter.class).isNotEqualTo(McpConversationLogService.class);
        assertThat(McpConversationLogWriter.class.isAnnotationPresent(org.springframework.stereotype.Component.class))
                .as("must be its own Spring bean for the proxy to wrap it")
                .isTrue();
    }

    /**
     * EclipseLink defers writes to commit, so a plain save would put the INSERT beyond the reach
     * of the code meant to report its failure. Only the flushing variant is available.
     */
    @Test
    public void theRepositoryOffersNoDeferredSave() {
        final boolean hasPlainSave = java.util.Arrays.stream(McpConversationLogRepository.class.getMethods())
                .anyMatch(m -> m.getName().equals("save"));

        assertThat(hasPlainSave).as("save() defers the INSERT to commit; only saveAndFlush may be used").isFalse();
    }
}
