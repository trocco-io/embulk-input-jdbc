package org.embulk.input.postgresql.getter;

import com.google.common.base.Optional;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.input.jdbc.AbstractJdbcInputPlugin.PluginTask;
import org.embulk.input.jdbc.JdbcColumn;
import org.embulk.input.jdbc.JdbcColumnOption;
import org.embulk.input.jdbc.JdbcInputConnection;
import org.embulk.input.jdbc.getter.ColumnGetter;
import org.embulk.input.jdbc.getter.ColumnGetterFactory;
import org.embulk.input.jdbc.getter.StringColumnGetter;
import org.embulk.input.jdbc.getter.TimestampColumnGetter;
import org.embulk.input.jdbc.getter.TimestampWithTimeZoneIncrementalHandler;
import org.embulk.input.jdbc.getter.TimestampWithoutTimeZoneIncrementalHandler;
import org.embulk.spi.Exec;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.time.TimestampFormatter;
import org.embulk.spi.type.Types;
import org.joda.time.DateTimeZone;

public class PostgreSQLColumnGetterFactory extends ColumnGetterFactory
{
    // Same default format as DateColumnGetter.DEFAULT_FORMAT in embulk-input-jdbc. The
    // base ColumnGetterFactory#newColumnGetter() uses this (not
    // TimestampColumnGetter.DEFAULT_FORMAT) even for the "timestamp" case, so this getter
    // must match that to avoid an unrelated formatting change. Both constants are
    // package-private there, so they cannot be referenced directly.
    private static final String TIMESTAMP_DEFAULT_FORMAT = "%Y-%m-%d";

    private final DateTimeZone sourceTimeZone;

    public PostgreSQLColumnGetterFactory(PageBuilder to, DateTimeZone defaultTimeZone)
    {
        this(to, defaultTimeZone, defaultTimeZone);
    }

    // defaultTimeZone controls output-string formatting only (role unchanged from
    // upstream); sourceTimeZone controls how timezone-less "timestamp" values are
    // interpreted (Calendar passed to ResultSet#getTimestamp). These are intentionally
    // kept as separate parameters rather than derived from the PluginTask here, so the
    // split is explicit at every call site (see PostgreSQLInputPlugin#newColumnGetters()).
    public PostgreSQLColumnGetterFactory(PageBuilder to, DateTimeZone defaultTimeZone, DateTimeZone sourceTimeZone)
    {
        super(to, defaultTimeZone);
        this.sourceTimeZone = sourceTimeZone;
    }

    @Override
    public ColumnGetter newColumnGetter(JdbcInputConnection con, PluginTask task, JdbcColumn column, JdbcColumnOption option)
    {
        if (column.getTypeName().equals("hstore") && getToType(option) == Types.JSON) {
            // converting hstore to json needs a special handling
            return new HstoreToJsonColumnGetter(to, Types.JSON);
        }

        if (column.getSqlType() == java.sql.Types.ARRAY) {
            return new ArrayColumnGetter(to, getToType(option));
        }

        if ("uuid".equals(column.getTypeName())) {
            return new StringColumnGetter(to, getToType(option));
        }

        ColumnGetter getter = super.newColumnGetter(con, task, column, option);

        // incremental loading wrapper
        switch (column.getTypeName()) {
        case "timestamptz":
            // pgjdbc ignores the Calendar argument for timestamptz (the server-provided
            // offset always wins), so the Calendar-less getter is already correct here.
            return new TimestampWithTimeZoneIncrementalHandler(getter);
        case "timestamp":
            return new TimestampWithoutTimeZoneIncrementalHandler(
                    withTimeZoneAwareFetch(getter, option));
        default:
            return getter;
        }
    }

    // TimestampColumnGetter#fetch() uses the Calendar-less ResultSet#getTimestamp(int)
    // overload, which pgjdbc interprets using the JVM default timezone for "timestamp"
    // (without time zone) columns, regardless of "timezone"/"default_timezone" config.
    // Replace it with a PostgreSQL-specific getter that uses the Calendar-aware overload
    // instead, so "source_timezone" actually controls value interpretation.
    //
    // Package-private (not private) so PostgreSQLColumnGetterFactoryTest can call it
    // directly with a non-TimestampColumnGetter getter, without needing an Embulk Exec
    // session (newTimestampFormatter() below requires one and is not reached in that case).
    ColumnGetter withTimeZoneAwareFetch(ColumnGetter getter, JdbcColumnOption option)
    {
        if (!(getter instanceof TimestampColumnGetter)) {
            return getter;
        }
        DateTimeZone zoneId = resolveColumnTimeZone(option, sourceTimeZone);
        TimestampFormatter formatter = newTimestampFormatter(option, zoneId);
        return new PostgreSQLTimestampColumnGetter(to, getToType(option), formatter, zoneId);
    }

    // Column-level "timezone" (column_options) takes precedence over "source_timezone"
    // when both are set. Extracted into its own method (package-private, static) so the
    // precedence itself is unit-testable without an Embulk Exec session, mirroring
    // PostgreSQLInputPlugin#resolveSourceTimeZone().
    static DateTimeZone resolveColumnTimeZone(JdbcColumnOption option, DateTimeZone sourceTimeZone)
    {
        return option.getTimeZone().or(sourceTimeZone);
    }

    private static interface FormatterIntlTask extends Task, TimestampFormatter.Task {}
    private static interface FormatterIntlColumnOption extends Task, TimestampFormatter.TimestampColumnOption {}

    // Mirrors the base ColumnGetterFactory#newTimestampFormatter(), which is private
    // there and therefore cannot be reused, but uses "zoneId" (source_timezone, falling
    // back to the column-level "timezone" override) instead of "defaultTimeZone" for the
    // formatter's default zone.
    private TimestampFormatter newTimestampFormatter(JdbcColumnOption option, DateTimeZone zoneId)
    {
        final ConfigSource configSource = Exec.newConfigSource();
        configSource.set("format", option.getTimestampFormat().isPresent()
                                   ? option.getTimestampFormat().get().getFormat()
                                   : TIMESTAMP_DEFAULT_FORMAT);
        configSource.set("timezone", zoneId);
        return new TimestampFormatter(
            Exec.newConfigSource().loadConfig(FormatterIntlTask.class),
            Optional.fromNullable(configSource.loadConfig(FormatterIntlColumnOption.class)));
    }

    @Override
    protected String sqlTypeToValueType(JdbcColumn column, int sqlType)
    {
        switch(column.getTypeName()) {
        case "json":
        case "jsonb":
            return "json";
        case "hstore":
        case "array":
            // array & hstore is converted to string by default
            return "string";
        default:
            return super.sqlTypeToValueType(column, sqlType);
        }
    }
}
