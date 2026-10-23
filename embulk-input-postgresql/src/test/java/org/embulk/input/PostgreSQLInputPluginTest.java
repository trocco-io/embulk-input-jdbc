package org.embulk.input;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Optional;
import org.embulk.input.PostgreSQLInputPlugin.PostgreSQLPluginTask;
import org.joda.time.DateTimeZone;
import org.junit.Test;

/**
 * resolveSourceTimeZone()が、source_timezone省略時に実行マシンのタイムゾーンへフォールバックする
 * ことを確認するテスト。Self-Hosted Runner対応のための分岐。
 */
public class PostgreSQLInputPluginTest
{
    @Test
    public void resolveSourceTimeZone_whenAbsent_fallsBackToSystemDefault()
    {
        PostgreSQLPluginTask task = mock(PostgreSQLPluginTask.class);
        when(task.getSourceTimezone()).thenReturn(Optional.<DateTimeZone>absent());

        DateTimeZone result = PostgreSQLInputPlugin.resolveSourceTimeZone(task);

        assertEquals(DateTimeZone.getDefault(), result);
    }

    @Test
    public void resolveSourceTimeZone_whenPresent_usesConfiguredZone()
    {
        PostgreSQLPluginTask task = mock(PostgreSQLPluginTask.class);
        when(task.getSourceTimezone()).thenReturn(Optional.of(DateTimeZone.forID("Asia/Tokyo")));

        DateTimeZone result = PostgreSQLInputPlugin.resolveSourceTimeZone(task);

        assertEquals(DateTimeZone.forID("Asia/Tokyo"), result);
    }
}
