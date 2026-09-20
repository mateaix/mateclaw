package vip.mate.goal.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Absolute persisted lease deadline; LocalDateTime is retained at scheduler API boundaries. */
final class GoalLeaseTime {
    private GoalLeaseTime() { }
    static long epoch(LocalDateTime value) { return value.atZone(ZoneId.systemDefault()).toEpochSecond(); }
    static LocalDateTime local(long epoch) { return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.systemDefault()); }
}
