package com.legacyaudit.engine.pg;

import java.util.Set;

/** Interval units the SQL library accepted after {@code INTERVAL 'n'}. */
final class Units {

    private static final Set<String> UNITS = Set.of("C", "CENT", "CENTS", "CENTURIES", "CENTURY", "D", "DAY", "DAYOFMONTH",
            "DAYOFWEEK", "DAYOFWEEKISO", "DAYOFWEEK_ISO", "DAYOFYEAR", "DAYS", "DD", "DEC", "DECADE", "DECADES", "DECS", "DOW",
            "DOW_ISO", "DOY", "DW", "DW_ISO", "DY", "EPOCH", "EPOCH_MICROSECOND", "EPOCH_MICROSECONDS", "EPOCH_MILLISECOND",
            "EPOCH_MILLISECONDS", "EPOCH_NANOSECOND", "EPOCH_NANOSECONDS", "EPOCH_SECOND", "EPOCH_SECONDS", "H", "HH", "HOUR",
            "HOURS", "HR", "HRS", "M", "MI", "MICROSEC", "MICROSECOND", "MICROSECONDS", "MICROSECS", "MIL", "MILLENIA",
            "MILLENNIUM", "MILLISEC", "MILLISECON", "MILLISECOND", "MILLISECONDS", "MILLISECS", "MILS", "MIN", "MINS", "MINUTE",
            "MINUTES", "MM", "MON", "MONS", "MONTH", "MONTHS", "MS", "MSEC", "MSECOND", "MSECONDS", "MSECS", "NANOSEC",
            "NANOSECOND", "NANOSECS", "NS", "NSEC", "NSECOND", "NSECONDS", "Q", "QTR", "QTRS", "QUARTER", "QUARTERS", "S", "SEC",
            "SECOND", "SECONDS", "SECS", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "TZH", "TZM", "US", "USEC", "USECOND", "USECONDS",
            "USECS", "W", "WEEK", "WEEKDAY", "WEEKDAY_ISO", "WEEKISO", "WEEKOFYEAR", "WEEKOFYEARISO", "WEEKOFYEAR_ISO",
            "WEEK_ISO", "WK", "WOY", "WY", "Y", "YEAR", "YEARS", "YR", "YRS", "YY", "YYY", "YYYY");

    private Units() {}

    static boolean isUnit(String up) {
        return UNITS.contains(up);
    }
}
