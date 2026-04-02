package com.raftpay.api.controller;

import com.raftpay.api.dto.ApiResponse;
import com.raftpay.banking.BankStateMachine;
import com.raftpay.raft.LogEntry;
import com.raftpay.raft.RaftNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/api/cluster")
@CrossOrigin(origins = "*")
public class ClusterController {

    private final RaftNode raftNode;
    private final BankStateMachine bankStateMachine;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${raftpay.peers:}")
    private String peersConfig;

    public ClusterController(RaftNode raftNode, BankStateMachine bankStateMachine) {
        this.raftNode = raftNode;
        this.bankStateMachine = bankStateMachine;
    }

    /**
     * Returns status of ALL nodes in the cluster by querying peers server-side.
     * This allows remote dashboards (via ngrok etc.) to see all nodes.
     */
    @GetMapping("/all-status")
    public ResponseEntity<List<Map<String, Object>>> getAllNodesStatus() {
        List<Map<String, Object>> allStatus = new ArrayList<>();

        // Add this node's status
        Map<String, Object> myStatus = raftNode.getStatus();
        myStatus.put("accountCount", bankStateMachine.getAccountCount());
        myStatus.put("reachable", true);
        allStatus.add(myStatus);

        // Query each peer's status via HTTP
        if (peersConfig != null && !peersConfig.isBlank()) {
            for (String peerEntry : peersConfig.split(",")) {
                peerEntry = peerEntry.trim();
                if (peerEntry.isEmpty()) continue;
                String[] parts = peerEntry.split("=", 2);
                if (parts.length != 2) continue;

                String peerId = parts[0].trim();
                String grpcAddress = parts[1].trim(); // e.g., raftpay-node2:9090
                String hostname = grpcAddress.split(":")[0];
                String httpUrl = "http://" + hostname + ":8080/api/cluster/status";

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> peerStatus = restTemplate.getForObject(httpUrl, Map.class);
                    if (peerStatus != null) {
                        peerStatus.put("reachable", true);
                        allStatus.add(peerStatus);
                    }
                } catch (Exception e) {
                    Map<String, Object> downNode = new LinkedHashMap<>();
                    downNode.put("nodeId", peerId);
                    downNode.put("state", "OFFLINE");
                    downNode.put("reachable", false);
                    allStatus.add(downNode);
                }
            }
        }

        return ResponseEntity.ok(allStatus);
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
