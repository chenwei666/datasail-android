package com.microsoft.sqlserver.jdbc;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

public class AndroidTemporalTest {
    private Object convert(LocalDateTime value, JDBCType target, SSType source, int scale) throws Exception {
        LocalDate base = source == SSType.DATETIME ? LocalDate.of(1900, 1, 1) : LocalDate.of(1, 1, 1);
        long ticks = value.toLocalTime().toNanoOfDay();
        if (source == SSType.DATETIME) ticks /= 1_000_000;
        return DDC.convertTemporalToObject(null, target, source, null,
                (int) ChronoUnit.DAYS.between(base, value.toLocalDate()), ticks, scale);
    }

    @Test public void timestampsMatchJdkAcrossCalendarBoundariesAndZones() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"UTC", "Asia/Shanghai", "America/New_York"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                for (String input : new String[] {"0001-01-01T00:00:00", "1582-10-04T12:00:00",
                        "2024-02-29T12:34:56.123456700", "2024-03-10T02:30:00", "9999-12-31T23:59:59.999999900"}) {
                    LocalDateTime value = LocalDateTime.parse(input);
                    assertEquals(zone + " " + input, java.sql.Timestamp.valueOf(value),
                            convert(value, JDBCType.TIMESTAMP, SSType.DATETIME2, 7));
                    assertEquals(java.sql.Date.valueOf(value.toLocalDate()),
                            convert(value, JDBCType.DATE, SSType.DATE, 0));
                }
            }
        } finally { TimeZone.setDefault(original); }
    }

    @Test public void dateAndDatetimeTextKeepLeapDayAndFraction() throws Exception {
        LocalDateTime value = LocalDateTime.parse("2024-02-29T12:34:56.123456700");
        assertEquals("2024-02-29", convert(value, JDBCType.CHAR, SSType.DATE, 0));
        assertEquals("2024-02-29 12:34:56.1234567", convert(value, JDBCType.CHAR, SSType.DATETIME2, 7));
        assertEquals("2024-02-29 12:34:56.123", convert(value, JDBCType.CHAR, SSType.DATETIME, 3));
    }

    @Test public void timeTextPreservesScaleAndSqlTimeRoundsAtMidnight() throws Exception {
        LocalDateTime value = LocalDateTime.parse("1900-01-01T23:59:59.999999900");
        assertEquals("23:59:59.9999999", convert(value, JDBCType.CHAR, SSType.TIME, 7));
        assertEquals("00:00:00", convert(value, JDBCType.TIME, SSType.TIME, 7).toString());
        assertEquals("12:34:56", convert(LocalDateTime.parse("1900-01-01T12:34:56"), JDBCType.CHAR, SSType.TIME, 0));
    }
}
