package dev.palworks.sleep;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** When pastured workers sleep, and whether a night's sleep leaves them Tired. */
class SleepScheduleTest {
    // As Cobblemon has them: night 12000-23999, day 0-11999 (roughly)
    private static boolean night(int time) {
        return time >= 12_000;
    }

    private static boolean day(int time) {
        return time < 12_000;
    }

    private static boolean any(int time) {
        return time >= 0; // every time of day
    }

    private static boolean ninetales(int time) {
        return (time >= 167 && time <= 11_833) || (time >= 13_702 && time <= 22_299);
    }

    @Test
    void aNightSleeperSleepsAtNight() {
        assertTrue(SleepSchedule.isBedtime(18_000, SleepScheduleTest::night, SleepScheduleTest::night));
        assertFalse(SleepSchedule.isBedtime(6_000, SleepScheduleTest::night, SleepScheduleTest::night));
    }

    @Test
    void aNocturnalOneSleepsByDay() {
        assertTrue(SleepSchedule.isBedtime(6_000, SleepScheduleTest::day, SleepScheduleTest::night));
        assertFalse(SleepSchedule.isBedtime(18_000, SleepScheduleTest::day, SleepScheduleTest::night));
    }

    @Test
    void customHoursAreKept() {
        // Ninetales' 167-11833 and 13702-22299
        IntPredicate custom = SleepScheduleTest::ninetales;
        assertTrue(SleepSchedule.isBedtime(5_000, custom, SleepScheduleTest::night));
        assertFalse(SleepSchedule.isBedtime(12_500, custom, SleepScheduleTest::night)); // its waking hours at dusk
        assertTrue(SleepSchedule.isBedtime(20_000, custom, SleepScheduleTest::night));
    }

    @Test
    void anyTimeMeansAtNight() {
        assertTrue(SleepSchedule.coversWholeDay(SleepScheduleTest::any));
        assertTrue(SleepSchedule.isBedtime(18_000, SleepScheduleTest::any, SleepScheduleTest::night));
        assertFalse(SleepSchedule.isBedtime(6_000, SleepScheduleTest::any, SleepScheduleTest::night));
        assertFalse(SleepSchedule.coversWholeDay(SleepScheduleTest::night));
    }

    @Test
    void theDayWrapsRound() {
        // Day 3's midnight is still midnight
        assertTrue(SleepSchedule.isBedtime(3L * 24_000 + 18_000, SleepScheduleTest::night, SleepScheduleTest::night));
        assertFalse(SleepSchedule.isBedtime(3L * 24_000 + 6_000, SleepScheduleTest::night, SleepScheduleTest::night));
    }

    @Test
    void underHalfANightInABedLeavesItTired() {
        assertFalse(SleepSchedule.wakesTired(1_000, 0));
        assertFalse(SleepSchedule.wakesTired(500, 500)); // half is enough
        assertTrue(SleepSchedule.wakesTired(499, 501));
        assertTrue(SleepSchedule.wakesTired(0, 1_000));
        assertFalse(SleepSchedule.wakesTired(0, 0)); // didn't sleep at all: nothing to be Tired from
    }
}
