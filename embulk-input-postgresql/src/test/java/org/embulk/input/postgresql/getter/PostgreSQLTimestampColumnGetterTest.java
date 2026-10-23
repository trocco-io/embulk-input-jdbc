package org.embulk.input.postgresql.getter;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import org.embulk.spi.Column;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.time.Timestamp;
import org.embulk.spi.time.TimestampFormatter;
import org.embulk.spi.type.Types;
import org.joda.time.DateTimeZone;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * PostgreSQLTimestampColumnGetterが、同じ壁時計の値をsource_timezone(Calendar引数)に応じて
 * 正しい絶対時刻(Instant)に変換することを確認するユニットテスト。UTC/Asia-Tokyo/nullの
 * 3ケースを確認する。
 *
 * <p>ここで再現しているResultSet#getTimestamp(int, Calendar)の挙動（wall-clock値をCalendarの
 * タイムゾーンで解釈する）はMockitoによるシミュレーションであり、実PostgreSQLに対する検証では
 * ない。実データベースでの実機検証を別途必ず行うこと。
 */
public class PostgreSQLTimestampColumnGetterTest
{
    private static final String WALL_CLOCK = "2026-10-12T09:00:00";
    private static final Column TS_COLUMN = new Column(0, "ts_col", Types.TIMESTAMP);

    @Test
    public void fetch_withUtcZone_interpretsWallClockAsUtc() throws SQLException
    {
        Instant result = getAndSetWithZone(DateTimeZone.UTC);
        assertEquals(Instant.parse("2026-10-12T09:00:00Z"), result);
    }

    @Test
    public void fetch_withAsiaTokyoZone_interpretsWallClockAsJst() throws SQLException
    {
        Instant result = getAndSetWithZone(DateTimeZone.forID("Asia/Tokyo"));
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), result);
    }

    @Test
    public void fetch_withFixedOffsetZone_interpretsWallClockUsingThatOffset() throws SQLException
    {
        // source_timezone accepts a fixed-offset string (e.g. "+09:00") in addition to an
        // IANA name; once parsed, it is just another DateTimeZone, so the same wall-clock
        // interpretation logic applies with no special-casing. Confirms that path produces
        // the same result as the equivalent IANA zone (Asia/Tokyo has no DST, so they are
        // always equivalent).
        Instant result = getAndSetWithZone(DateTimeZone.forID("+09:00"));
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), result);
    }

    @Test
    public void fetch_withDstObservingZone_usesWinterOffset() throws SQLException
    {
        // America/New_York observes DST; mid-January is outside DST (EST, UTC-5).
        Instant result = getAndSetWithZoneAndWallClock(DateTimeZone.forID("America/New_York"), "2026-01-15T09:00:00");
        assertEquals(Instant.parse("2026-01-15T14:00:00Z"), result);
    }

    @Test
    public void fetch_withDstObservingZone_usesSummerOffset() throws SQLException
    {
        // Same zone, mid-July is inside DST (EDT, UTC-4). A getter that only applied a
        // fixed offset (rather than full zone rules) would get this one hour wrong.
        Instant result = getAndSetWithZoneAndWallClock(DateTimeZone.forID("America/New_York"), "2026-07-15T09:00:00");
        assertEquals(Instant.parse("2026-07-15T13:00:00Z"), result);
    }

    @Test
    public void fetch_nullValue_setsNullOnColumn() throws SQLException
    {
        ResultSet rs = mock(ResultSet.class);
        when(rs.wasNull()).thenReturn(true);
        when(rs.getTimestamp(anyInt(), any(Calendar.class))).thenReturn(null);

        PageBuilder pageBuilder = mock(PageBuilder.class);
        PostgreSQLTimestampColumnGetter getter = newGetter(pageBuilder, DateTimeZone.UTC);

        getter.getAndSet(rs, 1, TS_COLUMN);

        verify(pageBuilder).setNull(TS_COLUMN);
    }

    private Instant getAndSetWithZone(DateTimeZone zoneId) throws SQLException
    {
        return getAndSetWithZoneAndWallClock(zoneId, WALL_CLOCK);
    }

    private Instant getAndSetWithZoneAndWallClock(DateTimeZone zoneId, String wallClock) throws SQLException
    {
        ResultSet rs = mock(ResultSet.class);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getTimestamp(anyInt(), any(Calendar.class)))
                .thenAnswer(invocation ->
                {
                    Calendar calendar = invocation.getArgument(1);
                    ZoneId calendarZone = calendar.getTimeZone().toZoneId();
                    Instant instant = LocalDateTime.parse(wallClock).atZone(calendarZone).toInstant();
                    return java.sql.Timestamp.from(instant);
                });

        PageBuilder pageBuilder = mock(PageBuilder.class);
        PostgreSQLTimestampColumnGetter getter = newGetter(pageBuilder, zoneId);

        getter.getAndSet(rs, 1, TS_COLUMN);

        ArgumentCaptor<Timestamp> captor = ArgumentCaptor.forClass(Timestamp.class);
        verify(pageBuilder).setTimestamp(eq(TS_COLUMN), captor.capture());
        return captor.getValue().getInstant();
    }

    private PostgreSQLTimestampColumnGetter newGetter(PageBuilder pageBuilder, DateTimeZone zoneId)
    {
        TimestampFormatter formatter = new TimestampFormatter("%Y-%m-%d", zoneId);
        return new PostgreSQLTimestampColumnGetter(pageBuilder, null, formatter, zoneId);
    }
}
