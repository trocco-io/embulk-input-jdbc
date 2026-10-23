package org.embulk.input.postgresql.getter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Locale;
import org.embulk.input.jdbc.getter.TimestampColumnGetter;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.time.Timestamp;
import org.embulk.spi.time.TimestampFormatter;
import org.embulk.spi.type.Type;
import org.joda.time.DateTimeZone;

/**
 * TimestampColumnGetter#fetch() reads the value via ResultSet#getTimestamp(int), a
 * Calendar-less overload. PostgreSQL's pgjdbc driver interprets this overload using the
 * JVM default timezone for TIMESTAMP (WITHOUT TIME ZONE) columns (verified against the
 * driver source), so a timezone-less timestamp column is always
 * misinterpreted as the machine's timezone rather than the declared "source_timezone".
 * This subclass uses the Calendar-aware overload instead, so that "source_timezone"
 * actually controls how the value is interpreted, not just how it is formatted when
 * output as a string.
 *
 * pgjdbc ignores the Calendar argument for TIMESTAMP WITH TIME ZONE columns (the
 * server-provided offset always wins there), so this getter only needs to be wired up
 * for the "timestamp" (without time zone) case; see PostgreSQLColumnGetterFactory.
 */
public class PostgreSQLTimestampColumnGetter extends TimestampColumnGetter
{

    private final Calendar calendar;

    public PostgreSQLTimestampColumnGetter(
            PageBuilder to, Type toType, TimestampFormatter timestampFormatter, DateTimeZone zoneId)
    {
        super(to, toType, timestampFormatter);
        // Locale.ROOT avoids Calendar.getInstance() returning a non-Gregorian calendar
        // (e.g. BuddhistCalendar, JapaneseImperialCalendar) under certain JVM default
        // locales, which would otherwise shift the interpreted year (the same pitfall
        // applies to any JDBC connector using Calendar-based interpretation).
        this.calendar = Calendar.getInstance(zoneId.toTimeZone(), Locale.ROOT);
    }

    @Override
    protected void fetch(ResultSet from, int fromIndex) throws SQLException
    {
        java.sql.Timestamp timestamp = from.getTimestamp(fromIndex, calendar);
        if (timestamp != null) {
            value = Timestamp.ofEpochSecond(timestamp.getTime() / 1000, timestamp.getNanos());
        }
    }
}
