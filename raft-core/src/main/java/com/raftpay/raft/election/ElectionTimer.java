package com.raftpay.raft.election;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Randomized election timeout timer.
 * When the timer expires without being reset, it triggers an election.
 */
public class ElectionTimer {

    private static final Logger log = LoggerFactory.getLogger(ElectionTimer.class);

    private final int minTimeout;
    private final int maxTimeout;
    private final Runnable onTimeout;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Thread timerThread;
    private volatile long deadline;

    public ElectionTimer(int minTimeoutMs, int maxTimeoutMs, Runnable onTimeout) {
        this.minTimeout = minTimeoutMs;
        this.maxTimeout = maxTimeoutMs;
        this.onTimeout = onTimeout;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            resetDeadline();
            timerThread = new Thread(this::runLoop, "election-timer");
            timerThread.setDaemon(true);
            timerThread.start();
        }
    }

    public void stop() {
        running.set(false);
        Thread t = timerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Reset the election timer. Called when:
     * - Receiving a valid AppendEntries (heartbeat) from the leader
     * - Granting a vote to a candidate
     * - Starting a new election
     */
    public void reset() {
        resetDeadline();
    }

    private void resetDeadline() {
        int timeout = ThreadLocalRandom.current().nextInt(minTimeout, maxTimeout + 1);
        this.deadline = System.currentTimeMillis() + timeout;
    }

    private void runLoop() {
        while (running.get()) {
            try {
                long now = System.currentTimeMillis();
                long remaining = deadline - now;

                if (remaining <= 0) {
                    // Timer expired — trigger election
                    log.debug("Election timeout expired");
                    onTimeout.run();
                    resetDeadline(); // reset for next round
                } else {
                    Thread.sleep(Math.min(remaining, 50)); // check every 50ms at most
                }
            } catch (InterruptedException e) {
                // Expected when stopping
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
