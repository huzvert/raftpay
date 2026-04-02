package com.raftpay.api.controller;

import com.raftpay.api.dto.ApiResponse;
import com.raftpay.banking.BankStateMachine;
import com.raftpay.raft.LogEntry;
import com.raftpay.raft.RaftNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/cluster")
@CrossOrigin(origins = "*")
public class ClusterController {

    private final RaftNode raftNode;
    private final BankStateMachine bankStateMachine;

    public ClusterController(RaftNode raftNode, BankStateMachine bankStateMachine) {
        this.raftNode = raftNode;
        this.bankStateMachine = bankStateMachine;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = raftNode.getStatus();
        status.put("accountCount", bankStateMachine.getAccountCount());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/log")
    public ResponseEntity<ApiResponse> getLog() {
        List<Map<String, Object>> logEntries = new ArrayList<>();
        List<LogEntry> entries = raftNode.getRaftLog().getAllEntries();

        for (LogEntry entry : entries) {
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("index", entry.getIndex());
            entryMap.put("term", entry.getTerm());
            entryMap.put("commandSize", entry.getCommand().length);

            // Try to decode command for display
            try {
                String cmdJson = new String(entry.getCommand());
                entryMap.put("command", cmdJson);
            } catch (Exception e) {
                entryMap.put("command", "[binary]");
            }

            boolean committed = entry.getIndex() <= raftNode.getCommitIndex();
            boolean applied = entry.getIndex() <= raftNode.getLastApplied();
            entryMap.put("committed", committed);
            entryMap.put("applied", applied);

            logEntries.add(entryMap);
        }

        return ResponseEntity.ok(ApiResponse.ok("Log entries", logEntries));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("nodeId", raftNode.getNodeId());
        health.put("raftState", raftNode.getState().name());
        health.put("term", raftNode.getCurrentTerm());
        health.put("leader", raftNode.getLeaderId());
        return ResponseEntity.ok(health);
    }
}
