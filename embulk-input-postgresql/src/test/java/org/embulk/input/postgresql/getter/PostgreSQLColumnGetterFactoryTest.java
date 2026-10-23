package org.embulk.input.postgresql.getter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Optional;
import org.embulk.input.jdbc.JdbcColumnOption;
import org.embulk.input.jdbc.getter.ColumnGetter;
import org.embulk.spi.PageBuilder;
import org.joda.time.DateTimeZone;
import org.junit.Test;

/**
 * column_options側の"timezone"がsource_timezoneより優先されること（コード上の仕様だが、
 * これまで検証が無かった箇所）、および
 * timestamp以外の列（date/time等）がsource_timezoneの影響を受けないことを確認するテスト。
 */
public class PostgreSQLColumnGetterFactoryTest
{
    @Test
    public void resolveColumnTimeZone_whenColumnOptionTimeZonePresent_takesPrecedenceOverSourceTimeZone()
    {
        JdbcColumnOption option = mock(JdbcColumnOption.class);
        when(option.getTimeZone()).thenReturn(Optional.of(DateTimeZone.forID("America/New_York")));

        DateTimeZone result = PostgreSQLColumnGetterFactory.resolveColumnTimeZone(option, DateTimeZone.forID("Asia/Tokyo"));

        assertEquals(DateTimeZone.forID("America/New_York"), result);
    }

    @Test
    public void resolveColumnTimeZone_whenColumnOptionTimeZoneAbsent_fallsBackToSourceTimeZone()
    {
        JdbcColumnOption option = mock(JdbcColumnOption.class);
        when(option.getTimeZone()).thenReturn(Optional.<DateTimeZone>absent());

        DateTimeZone result = PostgreSQLColumnGetterFactory.resolveColumnTimeZone(option, DateTimeZone.forID("Asia/Tokyo"));

        assertEquals(DateTimeZone.forID("Asia/Tokyo"), result);
    }

    @Test
    public void withTimeZoneAwareFetch_forNonTimestampGetter_returnsGetterUnchanged()
    {
        // date/time columns never produce a TimestampColumnGetter from the base factory,
        // so this confirms they are not wrapped (and therefore unaffected by
        // source_timezone), without needing an Embulk Exec session.
        PageBuilder pageBuilder = mock(PageBuilder.class);
        PostgreSQLColumnGetterFactory factory =
                new PostgreSQLColumnGetterFactory(pageBuilder, DateTimeZone.UTC, DateTimeZone.forID("Asia/Tokyo"));
        ColumnGetter nonTimestampGetter = mock(ColumnGetter.class);

        ColumnGetter result = factory.withTimeZoneAwareFetch(nonTimestampGetter, null);

        assertSame(nonTimestampGetter, result);
    }
}
