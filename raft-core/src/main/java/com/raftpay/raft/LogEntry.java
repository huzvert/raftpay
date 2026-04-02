package com.raftpay.raft;

import java.util.Arrays;

public class LogEntry {

    private final long term;
    private final long index;
    private final byte[] command;

    public LogEntry(long term, long index, byte[] command) {
        this.term = term;
        this.index = index;
        this.command = command;
    }

    public long getTerm() {
        return term;
    }

    public long getIndex() {
        return index;
    }

    public byte[] getCommand() {
        return command;
    }

    @Override
    public String toString() {
        return "LogEntry{term=" + term + ", index=" + index + ", cmdLen=" + (command != null ? command.length : 0) + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LogEntry logEntry = (LogEntry) o;
        return term == logEntry.term && index == logEntry.index && Arrays.equals(command, logEntry.command);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(term);
        result = 31 * result + Long.hashCode(index);
        result = 31 * result + Arrays.hashCode(command);
        return result;
    }
}
