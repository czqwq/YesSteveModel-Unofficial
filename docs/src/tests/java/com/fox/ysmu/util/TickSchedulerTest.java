package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class TickSchedulerTest {

    @Test
    void retriesWaitForTheNextIntervalInsteadOfRecursingOnTheCallingThread() {
        AtomicLong clock = new AtomicLong();
        TickScheduler scheduler = new TickScheduler(clock::get);
        int[] attempts = { 0 };
        TickScheduler.Handle[] retry = new TickScheduler.Handle[1];
        retry[0] = scheduler.scheduleEvery(50L, () -> {
            if (++attempts[0] == 60) retry[0].cancel();
        });

        scheduler.runDueTasks();
        assertEquals(0, attempts[0]);
        for (int tick = 1; tick <= 60; tick++) {
            clock.set(tick * 50L);
            scheduler.runDueTasks();
            scheduler.runDueTasks();
            assertEquals(tick, attempts[0], "a second drive at the same time must not retry again");
        }
        clock.addAndGet(50L);
        scheduler.runDueTasks();
        assertEquals(60, attempts[0]);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void clearCancelsOldSessionWaiters() {
        AtomicLong clock = new AtomicLong();
        TickScheduler scheduler = new TickScheduler(clock::get);
        int[] attempts = { 0 };
        TickScheduler.Handle waiter = scheduler.scheduleEvery(50L, () -> attempts[0]++);

        scheduler.clear();
        clock.set(5_000L);
        scheduler.runDueTasks();
        assertTrue(waiter.isCancelled());
        assertEquals(0, attempts[0]);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void restartingASyncRoundInsideACallbackDropsOldSnapshotAndDefersNewWaiters() {
        AtomicLong clock = new AtomicLong();
        TickScheduler scheduler = new TickScheduler(clock::get);
        int[] oldAttempts = { 0 };
        int[] newAttempts = { 0 };
        scheduler.scheduleEvery(50L, () -> {
            scheduler.clear();
            scheduler.scheduleEvery(50L, () -> newAttempts[0]++);
        });
        TickScheduler.Handle oldWaiter = scheduler.scheduleEvery(50L, () -> oldAttempts[0]++);

        clock.set(50L);
        scheduler.runDueTasks();
        assertTrue(oldWaiter.isCancelled());
        assertEquals(0, oldAttempts[0], "clear must cancel remaining tasks in the current iterator snapshot");
        assertEquals(0, newAttempts[0], "new round waits a full interval, not the rest of this tick");
        assertEquals(1, scheduler.pendingCount());
        clock.set(100L);
        scheduler.runDueTasks();
        assertEquals(1, newAttempts[0]);
    }

    @Test
    void drivingFromInsideACallbackCannotReenterEvenIfTheClockAdvanced() {
        AtomicLong clock = new AtomicLong();
        TickScheduler scheduler = new TickScheduler(clock::get);
        int[] attempts = { 0 };
        scheduler.scheduleEvery(50L, () -> {
            attempts[0]++;
            clock.addAndGet(50L);
            scheduler.runDueTasks();
        });

        clock.set(50L);
        scheduler.runDueTasks();
        assertEquals(1, attempts[0]);
        scheduler.runDueTasks();
        assertEquals(2, attempts[0], "only the next outer drive may run again");
    }

    @Test
    void cancelIsIdempotentAndMissedIntervalsDoNotProduceACatchUpLoop() {
        AtomicLong clock = new AtomicLong();
        TickScheduler scheduler = new TickScheduler(clock::get);
        int[] attempts = { 0 };
        TickScheduler.Handle waiter = scheduler.scheduleEvery(50L, () -> attempts[0]++);

        clock.set(5_000L);
        scheduler.runDueTasks();
        assertEquals(1, attempts[0]);
        waiter.cancel();
        waiter.cancel();
        clock.addAndGet(50L);
        scheduler.runDueTasks();
        assertEquals(1, attempts[0]);
        assertEquals(0, scheduler.pendingCount());
    }
}
