package com.raftpay.raft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory replicated log with support for Raft operations.
 * Index is 1-based (index 0 is a sentinel with term 0).
 */
public class RaftLog {

    private final List<LogEntry> entries;

    public RaftLog() {
        this.entries = new ArrayList<>();
        // Sentinel entry at index 0 to simplify boundary checks
        this.entries.add(new LogEntry(0, 0, new byte[0]));
    }

    public synchronized long getLastIndex() {
        return entries.size() - 1;
    }

    public synchronized long getLastTerm() {
        return entries.get(entries.size() - 1).getTerm();
    }

    public synchronized LogEntry getEntry(long index) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get((int) index);
    }

    public synchronized long getTermAt(long index) {
        LogEntry entry = getEntry(index);
        return entry != null ? entry.getTerm() : -1;
    }

    public synchronized long append(long term, byte[] command) {
        long nextIndex = entries.size();
        entries.add(new LogEntry(term, nextIndex, command));
        return nextIndex;
    }

    public synchronized void appendEntry(LogEntry entry) {
        // Ensure the entry goes at the right index
        while (entries.size() <= entry.getIndex()) {
            entries.add(null); // placeholder
        }
        entries.set((int) entry.getIndex(), entry);
    }

    /**
     * Remove all entries from the given index onwards (inclusive).
     * Used when a follower detects a conflict with the leader's log.
     */
    public synchronized void truncateFrom(long fromIndex) {
        if (fromIndex <= 0) return; // never remove sentinel
        while (entries.size() > fromIndex) {
            entries.remove(entries.size() - 1);
        }
    }

    /**
     * Get all entries from startIndex to the end of the log.
     */
    public synchronized List<LogEntry> getEntriesFrom(long startIndex) {
        if (startIndex >= entries.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(entries.subList((int) startIndex, entries.size()));
    }

    /**
     * Get all entries (excluding sentinel). Used for persistence.
     */
    public synchronized List<LogEntry> getAllEntries() {
        if (entries.size() <= 1) return Collections.emptyList();
        return new ArrayList<>(entries.subList(1, entries.size()));
    }

    /**
     * Restore log from persisted entries.
     */
    public synchronized void restore(List<LogEntry> persistedEntries) {
        entries.clear();
        entries.add(new LogEntry(0, 0, new byte[0])); // sentinel
        for (LogEntry entry : persistedEntries) {
            entries.add(entry);
        }
    }

    public synchronized int size() {
        return entries.size() - 1; // exclude sentinel
    }
}
