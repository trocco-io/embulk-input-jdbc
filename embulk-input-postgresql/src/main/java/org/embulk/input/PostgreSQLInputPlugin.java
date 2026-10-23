package org.embulk.input;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigException;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.input.jdbc.AbstractJdbcInputPlugin;
import org.embulk.input.jdbc.JdbcColumn;
import org.embulk.input.jdbc.JdbcColumnOption;
import org.embulk.input.jdbc.JdbcInputConnection.BatchSelect;
import org.embulk.input.jdbc.JdbcInputConnection.PreparedQuery;
import org.embulk.input.jdbc.JdbcSchema;
import org.embulk.input.jdbc.getter.ColumnGetter;
import org.embulk.input.jdbc.getter.ColumnGetterFactory;
import org.embulk.input.postgresql.PostgreSQLInputConnection;
import org.embulk.input.postgresql.getter.PostgreSQLColumnGetterFactory;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Column;
import org.embulk.spi.DataException;
import org.embulk.spi.Exec;
import org.embulk.spi.InputPlugin;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.PageOutput;
import org.embulk.spi.Schema;
import org.joda.time.DateTimeZone;

import static java.util.Locale.ENGLISH;

public class PostgreSQLInputPlugin
        extends AbstractJdbcInputPlugin
{
    public interface PostgreSQLPluginTask
            extends PluginTask
    {
        @Config("driver_path")
        @ConfigDefault("null")
        public Optional<String> getDriverPath();

        @Config("host")
        public String getHost();

        @Config("port")
        @ConfigDefault("5432")
        public int getPort();

        @Config("user")
        public String getUser();

        @Config("password")
        @ConfigDefault("\"\"")
        public String getPassword();

        @Config("database")
        public String getDatabase();

        @Config("schema")
        @ConfigDefault("\"public\"")
        public String getSchema();

        @Config("ssl")
        @ConfigDefault("false")
        public boolean getSsl();

        @Config("application_name")
        @ConfigDefault("\"embulk-input-postgresql\"")
        public String getApplicationName();

        // Unlike "default_timezone" (which always resolves to a DateTimeZone, defaulting
        // to "UTC" via @ConfigDefault when omitted), this field stays absent when
        // omitted. See PostgreSQLInputPlugin#resolveSourceTimeZone() for why that
        // distinction matters: PostgreSQL transfers can run on Self-Hosted Runners
        // (customer-controlled machines), so the "unset" fallback must be the executing
        // machine's own timezone, not a value baked in at parse time.
        //
        // Declared as Optional<DateTimeZone> (not Optional<String>) so that an invalid
        // value is rejected at config-load time with the same ConfigException behavior
        // as "default_timezone", rather than surfacing later as an unrelated runtime
        // exception.
        @Config("source_timezone")
        @ConfigDefault("null")
        public Optional<DateTimeZone> getSourceTimezone();
    }

    @Override
    protected Class<? extends PluginTask> getTaskClass()
    {
        return PostgreSQLPluginTask.class;
    }

    @Override
    protected PostgreSQLInputConnection newConnection(PluginTask task) throws SQLException
    {
        PostgreSQLPluginTask t = (PostgreSQLPluginTask) task;

        loadDriver("org.postgresql.Driver", t.getDriverPath());

        String url = String.format("jdbc:postgresql://%s:%d/%s",
                t.getHost(), t.getPort(), t.getDatabase());

        Properties props = new Properties();
        props.setProperty("user", t.getUser());
        props.setProperty("password", t.getPassword());
        props.setProperty("loginTimeout", String.valueOf(t.getConnectTimeout())); // seconds
        props.setProperty("socketTimeout", String.valueOf(t.getSocketTimeout())); // seconds

        // Enable keepalive based on tcp_keepalive_time, tcp_keepalive_intvl and tcp_keepalive_probes kernel parameters.
        // Socket options TCP_KEEPCNT, TCP_KEEPIDLE, and TCP_KEEPINTVL are not configurable.
        props.setProperty("tcpKeepAlive", "true");

        if (t.getSsl()) {
            // TODO add ssl_verify (boolean) option to allow users to verify certification.
            //      see embulk-input-ftp for SSL implementation.
            props.setProperty("ssl", "true");
            props.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");  // disable server-side validation
        }
        // setting ssl=false enables SSL. See org.postgresql.core.v3.openConnectionImpl.

        props.setProperty("ApplicationName", t.getApplicationName());

        props.putAll(t.getOptions());
        logConnectionProperties(url, props);

        Connection con = DriverManager.getConnection(url, props);
        try {
            PostgreSQLInputConnection c = new PostgreSQLInputConnection(con, t.getSchema());
            con = null;
            return c;
        } finally {
            if (con != null) {
                con.close();
            }
        }
    }

    @Override
    protected ColumnGetterFactory newColumnGetterFactory(PageBuilder pageBuilder, DateTimeZone dateTimeZone)
    {
        // Used by AbstractJdbcInputPlugin#transaction() only, to determine each column's
        // embulk type while building the output Schema. No row values are actually read
        // through this path, so there is no sourceTimeZone to wire in here; the run()-time
        // path below constructs PostgreSQLColumnGetterFactory directly instead.
        return new PostgreSQLColumnGetterFactory(pageBuilder, dateTimeZone);
    }

    // Resolves the timezone used to interpret "timestamp" (without time zone) values.
    // When "source_timezone" is unset, falls back to the executing machine's timezone
    // (DateTimeZone.getDefault()) rather than a fixed "UTC", so that Self-Hosted Runner
    // jobs keep interpreting values the same way they always have (via whichever
    // timezone that machine happens to run in), matching TROCCO's own managed workers
    // only incidentally (they run with TZ unset, i.e. UTC).
    static DateTimeZone resolveSourceTimeZone(PostgreSQLPluginTask task)
    {
        return task.getSourceTimezone().or(DateTimeZone.getDefault());
    }

    // --- Everything below is copied from AbstractJdbcInputPlugin#run() and its private
    // helpers (newColumnGetters/columnOptionOf/fetch/LastRecordStore), because that
    // run() calls the private newColumnGetters() which always builds the factory from
    // task.getDefaultTimeZone() alone. There is no protected seam to inject
    // sourceTimeZone as a second, independent timezone without duplicating run() itself
    // (the same constraint applies to any similarly-structured JDBC connector). Only the
    // single line constructing PostgreSQLColumnGetterFactory is changed from upstream.

    private static class LastRecordStore
    {
        private final List<Integer> columnIndexes;
        private final JsonNode[] lastValues;
        private final List<String> columnNames;

        public LastRecordStore(List<Integer> columnIndexes, List<String> columnNames)
        {
            this.columnIndexes = columnIndexes;
            this.lastValues = new JsonNode[columnIndexes.size()];
            this.columnNames = columnNames;
        }

        public void accept(List<ColumnGetter> getters)
            throws SQLException
        {
            for (int i = 0; i < columnIndexes.size(); i++) {
                lastValues[i] = getters.get(columnIndexes.get(i)).encodeToJson();
            }
        }

        public List<JsonNode> getList()
        {
            ImmutableList.Builder<JsonNode> builder = ImmutableList.builder();
            for (int i = 0; i < lastValues.length; i++) {
                if (lastValues[i] == null || lastValues[i].isNull()) {
                    throw new DataException(String.format(ENGLISH,
                            "incremental_columns can't include null values but the last row is null at column '%s'",
                            columnNames.get(i)));
                }
                builder.add(lastValues[i]);
            }
            return builder.build();
        }
    }

    @Override
    public TaskReport run(TaskSource taskSource,
            Schema schema, int taskIndex,
            PageOutput output)
    {
        PostgreSQLPluginTask task = taskSource.loadTask(PostgreSQLPluginTask.class);

        PreparedQuery builtQuery = task.getBuiltQuery();
        JdbcSchema querySchema = task.getQuerySchema();
        BufferAllocator allocator = task.getBufferAllocator();
        PageBuilder pageBuilder = new PageBuilder(allocator, schema, output);

        long totalRows = 0;

        LastRecordStore lastRecordStore = null;

        try (PostgreSQLInputConnection con = newConnection(task)) {
            if (task.getBeforeSelect().isPresent()) {
                con.executeUpdate(task.getBeforeSelect().get());
            }

            List<ColumnGetter> getters = newColumnGetters(con, task, querySchema, pageBuilder);
            try (BatchSelect cursor = con.newSelectCursor(builtQuery, getters, task.getFetchRows(), task.getSocketTimeout())) {
                while (true) {
                    long rows = fetch(cursor, getters, pageBuilder);
                    if (rows <= 0L) {
                        break;
                    }
                    totalRows += rows;
                }
            }

            if (task.getIncremental() && totalRows > 0) {
                lastRecordStore = new LastRecordStore(task.getIncrementalColumnIndexes(), task.getIncrementalColumns());
                lastRecordStore.accept(getters);
            }

            pageBuilder.finish();

            // after_select runs after pageBuilder.finish because pageBuilder.finish may fail.
            if (task.getAfterSelect().isPresent()) {
                con.executeUpdate(task.getAfterSelect().get());
            }
            con.commit();

        } catch (SQLException ex) {
            throw Throwables.propagate(ex);
        }

        TaskReport report = Exec.newTaskReport();
        if (lastRecordStore != null) {
            report.set("last_record", lastRecordStore.getList());
        }

        return report;
    }

    private List<ColumnGetter> newColumnGetters(PostgreSQLInputConnection con, PostgreSQLPluginTask task, JdbcSchema querySchema, PageBuilder pageBuilder)
            throws SQLException
    {
        // This is the one line that differs from AbstractJdbcInputPlugin#newColumnGetters():
        // defaultTimeZone (formatter) and sourceTimeZone (value interpretation) are
        // resolved and passed independently, instead of building the factory from
        // task.getDefaultTimeZone() alone.
        ColumnGetterFactory factory = new PostgreSQLColumnGetterFactory(
                pageBuilder, task.getDefaultTimeZone(), resolveSourceTimeZone(task));
        ImmutableList.Builder<ColumnGetter> getters = ImmutableList.builder();
        for (JdbcColumn c : querySchema.getColumns()) {
            JdbcColumnOption columnOption = columnOptionOf(task.getColumnOptions(), task.getDefaultColumnOptions(), c, factory.getJdbcType(c.getSqlType()));
            getters.add(factory.newColumnGetter(con, task, c, columnOption));
        }
        return getters.build();
    }

    private static JdbcColumnOption columnOptionOf(Map<String, JdbcColumnOption> columnOptions, Map<String, JdbcColumnOption> defaultColumnOptions, JdbcColumn targetColumn, String targetColumnSQLType)
    {
        JdbcColumnOption columnOption = columnOptions.get(targetColumn.getName());
        if (columnOption == null) {
            String foundName = null;
            for (Map.Entry<String, JdbcColumnOption> entry : columnOptions.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(targetColumn.getName())) {
                    if (columnOption != null) {
                        throw new ConfigException(String.format("Cannot specify column '%s' because both '%s' and '%s' exist in column_options.",
                                targetColumn.getName(), foundName, entry.getKey()));
                    }
                    foundName = entry.getKey();
                    columnOption = entry.getValue();
                }
            }
        }

        return Optional
                .fromNullable(columnOption)
                .or(Optional.fromNullable(defaultColumnOptions.get(targetColumnSQLType)))
                .or(
                    // default column option
                    new Supplier<JdbcColumnOption>()
                    {
                        public JdbcColumnOption get()
                        {
                            return Exec.newConfigSource().loadConfig(JdbcColumnOption.class);
                        }
                    });
    }

    private long fetch(BatchSelect cursor,
            List<ColumnGetter> getters, PageBuilder pageBuilder) throws SQLException
    {
        ResultSet result = cursor.fetch();
        if (result == null || !result.next()) {
            return 0;
        }

        List<Column> columns = pageBuilder.getSchema().getColumns();
        long rows = 0;
        long reportRows = 500;
        do {
            for (int i=0; i < getters.size(); i++) {
                int index = i + 1;  // JDBC column index begins from 1
                getters.get(i).getAndSet(result, index, columns.get(i));
            }
            pageBuilder.addRecord();
            rows++;
            if (rows % reportRows == 0) {
                logger.info(String.format("Fetched %,d rows.", rows));
                reportRows *= 2;
            }
        } while (result.next());

        return rows;
    }
}
