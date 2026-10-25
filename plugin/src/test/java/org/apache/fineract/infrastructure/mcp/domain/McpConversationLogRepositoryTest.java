package org.apache.fineract.infrastructure.mcp.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shape of the repository is part of the guarantee, not a detail.
 *
 * <p>A conversation record that can be edited or deleted answers nothing later. The interface is
 * narrowed to the three methods the plugin uses so that removing a turn is not something anyone
 * can do by accident, and these tests fail if the narrowing is ever undone, for instance by
 * someone extending {@code JpaRepository} again for the convenience of one extra finder.
 */
public class McpConversationLogRepositoryTest {

    private static List<String> methodNames() {
        return Arrays.stream(McpConversationLogRepository.class.getMethods()).map(Method::getName).sorted().toList();
    }

    @Test
    public void exposesNoWayToDeleteATurn() {
        assertThat(methodNames())
                .as("the institution's record of what its assistant said must not be removable through this API")
                .noneMatch(name -> name.toLowerCase().contains("delete") || name.toLowerCase().contains("remove"));
    }

    @Test
    public void exposesNothingBeyondTheThreeMethodsThePluginUses() {
        assertThat(methodNames()).containsExactlyInAnyOrder("saveAndFlush",
                "findByConversationIdAndUserIdOrderByIdAsc", "findByUserIdOrderByIdDesc");
    }

    /**
     * Every read is scoped by user. Knowing a conversation id is not a right to read it, and a
     * finder without the officer in its name would hand any client every officer's transcripts.
     */
    @Test
    public void everyFinderIsScopedToAUser() {
        assertThat(methodNames()).filteredOn(name -> name.startsWith("findBy"))
                .allMatch(name -> name.contains("UserId"));
    }
}
