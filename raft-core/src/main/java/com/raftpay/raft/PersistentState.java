package com.raftpay.raft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Handles durable persistence of Raft state:
 * - currentTerm
 * - votedFor
 * - log entries
 *
 * Uses atomic writes (write to temp + rename) to prevent corruption.
 */
public class PersistentState {

    private static final Logger log = LoggerFactory.getLogger(PersistentState.class);
    private static final String STATE_FILE = "raft-state.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dataDir;
    private final Path stateFile;

    public PersistentState(String dataDirPath) {
        this.dataDir = Path.of(dataDirPath);
        this.stateFile = dataDir.resolve(STATE_FILE);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create data directory: " + dataDirPath, e);
        }
    }

    public void save(long currentTerm, String votedFor, RaftLog raftLog) {
        StateData data = new StateData();
        data.currentTerm = currentTerm;
        data.votedFor = votedFor;
        data.entries = new ArrayList<>();

        for (LogEntry entry : raftLog.getAllEntries()) {
            EntryData ed = new EntryData();
            ed.term = entry.getTerm();
            ed.index = entry.getIndex();
            ed.command = Base64.getEncoder().encodeToString(entry.getCommand());
            data.entries.add(ed);
        }

        // Atomic write: write to temp file, then rename
        Path tempFile = dataDir.resolve(STATE_FILE + ".tmp");
        try {
            Files.writeString(tempFile, GSON.toJson(data));
            Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to persist state", e);
        }
    }

    public SavedState load() {
        if (!Files.exists(stateFile)) {
            return null;
        }
        try {
            String json = Files.readString(stateFile);
            StateData data = GSON.fromJson(json, StateData.class);

            List<LogEntry> entries = new ArrayList<>();
            if (data.entries != null) {
                for (EntryData ed : data.entries) {
                    byte[] cmd = Base64.getDecoder().decode(ed.command);
                    entries.add(new LogEntry(ed.term, ed.index, cmd));
                }
            }

            return new SavedState(data.currentTerm, data.votedFor, entries);
        } catch (IOException e) {
            log.error("Failed to load persisted state", e);
            return null;
        }
    }

    // JSON data classes
    private static class StateData {
        long currentTerm;
        String votedFor;
        List<EntryData> entries;
    }

    private static class EntryData {
        long term;
        long index;
        String command; // Base64 encoded
    }

    // Return type for loaded state
    public record SavedState(long currentTerm, String votedFor, List<LogEntry> entries) {}
}
