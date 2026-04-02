package com.raftpay.raft;

import java.util.Map;
import java.util.HashMap;

public class RaftConfig {

    private final String nodeId;
    private final int grpcPort;
    private final Map<String, String> peers; // peerId -> "host:port"

    // Timing configuration (milliseconds)
    private final int electionTimeoutMin;
    private final int electionTimeoutMax;
    private final int heartbeatInterval;

    // Persistence
    private final String dataDir;

    private RaftConfig(Builder builder) {
        this.nodeId = builder.nodeId;
        this.grpcPort = builder.grpcPort;
        this.peers = Map.copyOf(builder.peers);
        this.electionTimeoutMin = builder.electionTimeoutMin;
        this.electionTimeoutMax = builder.electionTimeoutMax;
        this.heartbeatInterval = builder.heartbeatInterval;
        this.dataDir = builder.dataDir;
    }

    public String getNodeId() { return nodeId; }
    public int getGrpcPort() { return grpcPort; }
    public Map<String, String> getPeers() { return peers; }
    public int getElectionTimeoutMin() { return electionTimeoutMin; }
    public int getElectionTimeoutMax() { return electionTimeoutMax; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public String getDataDir() { return dataDir; }

    public int getClusterSize() {
        return peers.size() + 1; // peers + self
    }

    public int getMajority() {
        return (getClusterSize() / 2) + 1;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String nodeId;
        private int grpcPort = 9090;
        private final Map<String, String> peers = new HashMap<>();
        private int electionTimeoutMin = 300;
        private int electionTimeoutMax = 500;
        private int heartbeatInterval = 100;
        private String dataDir = "./data";

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder grpcPort(int port) { this.grpcPort = port; return this; }
        public Builder peer(String id, String address) { this.peers.put(id, address); return this; }
        public Builder peers(Map<String, String> peers) { this.peers.putAll(peers); return this; }
        public Builder electionTimeoutMin(int ms) { this.electionTimeoutMin = ms; return this; }
        public Builder electionTimeoutMax(int ms) { this.electionTimeoutMax = ms; return this; }
        public Builder heartbeatInterval(int ms) { this.heartbeatInterval = ms; return this; }
        public Builder dataDir(String dir) { this.dataDir = dir; return this; }

        public RaftConfig build() {
            if (nodeId == null || nodeId.isEmpty()) {
                throw new IllegalArgumentException("nodeId is required");
            }
            return new RaftConfig(this);
        }
    }
}
