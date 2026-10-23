package org.apache.fineract.infrastructure.mcp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the real migration against a real database.
 *
 * <p>Every other test in this plugin mocks the repository, so until now nothing had ever executed
 * the changelog. A migration that is only read is a migration nobody has tried: an unresolved
 * property, a column type the database rejects, or a changeSet that cannot run twice would all
 * have shown up for the first time against a tenant's database.
 *
 * <p>The database here is H2 rather than PostgreSQL or MySQL, which is the honest limit of this
 * test. It cannot prove how {@code TIMESTAMP WITH TIME ZONE} behaves on PostgreSQL. What it does
 * prove is that the changelog parses, that every property resolves, that the table and its
 * constraints are created as described, and that running it a second time is safe. Testcontainers
 * is already a dependency of this module, so the same test can be pointed at a real MariaDB or
 * PostgreSQL once a Docker daemon is available.
 *
 * <p>No Liquibase context is set, which is deliberate: {@code McpPluginLiquibaseConfiguration}
 * sets none either, and a test that configured one would be testing something that never happens.
 */
public class McpConversationLogMigrationTest {

    private static final String CHANGELOG = "db/changelog/tenant/module/mcp/module-changelog-master.xml";

    private String url;

    private Connection connection;

    @BeforeEach
    void openDatabase() throws SQLException {
        // A database of its own per test, kept alive by DB_CLOSE_DELAY so that closing any one
        // connection does not discard the schema the test is still inspecting.
        url = "jdbc:h2:mem:mcp-" + System.identityHashCode(this) + ";DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
    }

    /**
     * Runs the changelog on a connection of its own.
     *
     * <p>Closing Liquibase closes the connection it was handed, which would take the test's own
     * connection with it. Giving it a separate one also mirrors production more closely, where
     * Liquibase runs against the pool rather than against anything a caller holds open.
     */
    private void migrate() throws Exception {
        try (Connection migrationConnection = DriverManager.getConnection(url, "sa", "")) {
            final Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(migrationConnection));
            final Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }

    private Map<String, String> columnsOfConversationLog() throws SQLException {
        final Map<String, String> columns = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = 'MCP_CONVERSATION_LOG'")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.put(rows.getString(1).toLowerCase(), rows.getString(2) + "/" + rows.getString(3));
                }
            }
        }
        return columns;
    }

    private long insertTurn(final String conversationId, final Long userId, final String prompt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO mcp_conversation_log (conversation_id, user_id, username, prompt, response, model, "
                        + "provider, client_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, conversationId);
            if (userId == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, userId);
            }
            statement.setString(3, "priya");
            statement.setString(4, prompt);
            statement.setString(5, "Aisha Bello has one active loan.");
            statement.setString(6, "qwen/qwen3.6-27b");
            statement.setString(7, "groq");
            statement.setNull(8, java.sql.Types.BIGINT);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    // ── the migration runs at all ───────────────────────────────────────────

    @Test
    public void createsTheTableWithEveryColumnTheEntityMapsTo() throws Exception {
        migrate();

        assertThat(columnsOfConversationLog().keySet()).containsExactlyInAnyOrder("id", "conversation_id", "user_id",
                "username", "prompt", "response", "model", "provider", "client_id", "created_at");
    }

    /**
     * The property has to resolve. On a database matching neither dbms= line the fallback applies;
     * without it the column type would be the literal text of the token and the migration would
     * fail with a message naming nothing useful.
     */
    @Test
    public void resolvesTheTimestampPropertyRatherThanLeavingTheTokenInPlace() throws Exception {
        migrate();

        final String createdAt = columnsOfConversationLog().get("created_at");
        assertThat(createdAt).isNotNull();
        assertThat(createdAt).doesNotContain("$").doesNotContain("timestamp.type");
        assertThat(createdAt).contains("/NO");
    }

    @Test
    public void keepsTheColumnsTheRecordCannotDoWithoutNotNull() throws Exception {
        migrate();

        final Map<String, String> columns = columnsOfConversationLog();
        assertThat(columns.get("conversation_id")).endsWith("/NO");
        assertThat(columns.get("prompt")).endsWith("/NO");
        assertThat(columns.get("response")).endsWith("/YES");
        assertThat(columns.get("user_id")).endsWith("/YES");
    }

    @Test
    public void buildsTheIndexesReadsDependOn() throws Exception {
        migrate();

        final List<String> indexes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE UPPER(TABLE_NAME) = 'MCP_CONVERSATION_LOG'")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    indexes.add(rows.getString(1).toLowerCase());
                }
            }
        }

        assertThat(indexes).contains("idx_mcp_conversation_thread", "idx_mcp_conversation_user",
                "idx_mcp_conversation_date");
    }

    /** A tenant that restarts must not fail on a changelog it has already applied. */
    @Test
    public void canRunASecondTimeWithoutFailing() throws Exception {
        migrate();
        migrate();

        assertThat(columnsOfConversationLog()).isNotEmpty();
    }

    // ── the constraints actually bite ───────────────────────────────────────

    @Test
    public void refusesATurnWithNoConversationToGroupItBy() throws Exception {
        migrate();

        assertThatThrownBy(() -> insertTurn(null, 12L, "find aisha")).isInstanceOf(SQLException.class);
    }

    @Test
    public void refusesATurnWithNoPrompt() throws Exception {
        migrate();

        assertThatThrownBy(() -> insertTurn("conv-1", 12L, null)).isInstanceOf(SQLException.class);
    }

    // ── ordering comes from the id, which is the whole design ───────────────

    /**
     * The reason there is no sequence_no column. Order within a conversation is the order the
     * database assigned ids in, so recording never has to read anything before it writes.
     */
    @Test
    public void handsOutAscendingIdsSoOrderNeedsNoStoredPosition() throws Exception {
        migrate();

        final long first = insertTurn("conv-1", 12L, "find the client aisha");
        final long second = insertTurn("conv-1", 12L, "and her repayment schedule?");
        final long third = insertTurn("conv-1", 12L, "when is the next instalment?");

        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);
    }

    /** Two officers may hold conversations with the same id without colliding. */
    @Test
    public void allowsTheSameConversationIdForDifferentOfficers() throws Exception {
        migrate();

        insertTurn("conv-shared", 12L, "priya asks");
        insertTurn("conv-shared", 44L, "amara asks");

        try (PreparedStatement statement = connection
                .prepareStatement("SELECT COUNT(*) FROM mcp_conversation_log WHERE conversation_id = 'conv-shared'")) {
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
        }
    }

    /** The query the history tool runs, against a real database rather than a mock. */
    @Test
    public void readsBackOnlyTheAskingOfficersTurns() throws Exception {
        migrate();

        insertTurn("conv-1", 12L, "priya first");
        insertTurn("conv-1", 44L, "amara in the same conversation id");
        insertTurn("conv-1", 12L, "priya second");

        final List<String> priyasPrompts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT prompt FROM mcp_conversation_log WHERE conversation_id = ? AND user_id = ? ORDER BY id ASC")) {
            statement.setString(1, "conv-1");
            statement.setLong(2, 12L);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    priyasPrompts.add(rows.getString(1));
                }
            }
        }

        assertThat(priyasPrompts).containsExactly("priya first", "priya second");
    }

    // ── a long turn survives intact ─────────────────────────────────────────

    /**
     * MySQL's {@code TEXT} holds 65,535 bytes and, outside strict mode, truncates anything larger
     * without failing. An officer pasting a statement into the assistant passes that easily, and a
     * record that silently keeps most of what was said is worse than one that refuses. The
     * changelog therefore declares these columns as CLOB, which is LONGTEXT on MySQL and MariaDB.
     *
     * <p>This database maps both TEXT and CLOB to the same large-object type, so this test cannot
     * tell the two Liquibase types apart. What it does prove is that the column is a large-object
     * type and not a VARCHAR, and that a value well past the MySQL TEXT ceiling round-trips
     * unchanged.
     */
    @Test
    public void keepsALongPromptWholeRatherThanTruncatingIt() throws Exception {
        migrate();

        final String longPrompt = "the client asked about her schedule. ".repeat(3000);
        assertThat(longPrompt.length()).isGreaterThan(65_535);

        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mcp_conversation_log "
                + "(conversation_id, user_id, prompt, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
            insert.setString(1, "conv-long");
            insert.setLong(2, 12L);
            insert.setString(3, longPrompt);
            insert.executeUpdate();
        }

        try (PreparedStatement read = connection
                .prepareStatement("SELECT prompt FROM mcp_conversation_log WHERE conversation_id = 'conv-long'")) {
            try (ResultSet rows = read.executeQuery()) {
                rows.next();
                assertThat(rows.getString(1)).hasSameSizeAs(longPrompt).isEqualTo(longPrompt);
            }
        }
    }

    /** Neither text column may end up a bounded VARCHAR, whatever the database calls its own. */
    @Test
    public void declaresThePromptAndResponseAsLargeText() throws Exception {
        migrate();

        final Map<String, String> columns = columnsOfConversationLog();
        assertThat(columns.get("prompt")).doesNotContain("VARCHAR").doesNotContain("CHARACTER VARYING");
        assertThat(columns.get("response")).doesNotContain("VARCHAR").doesNotContain("CHARACTER VARYING");
    }
}
