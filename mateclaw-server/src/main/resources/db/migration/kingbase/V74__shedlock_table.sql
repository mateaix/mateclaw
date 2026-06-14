-- V74: ShedLock distributed-lock table (RFC-03 Lane G2).
-- Backs the LockProvider configured in vip.mate.cron.config.ShedLockConfig
-- so a multi-instance deployment fires each cron job exactly once per tick.
-- Single-node setups are unaffected (acquiring the lock from the only node
-- always succeeds trivially).
--
-- Schema is the canonical ShedLock layout from
-- https://github.com/lukas-krecan/ShedLock#configure-lockprovider.

CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP(3) NOT NULL,
    locked_at   TIMESTAMP(3) NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
