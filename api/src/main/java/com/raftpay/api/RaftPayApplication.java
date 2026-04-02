package com.raftpay.api;

import com.raftpay.banking.BankStateMachine;
import com.raftpay.raft.RaftConfig;
import com.raftpay.raft.RaftNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class RaftPayApplication {

    private static final Logger log = LoggerFactory.getLogger(RaftPayApplication.class);

    @Value("${raftpay.node.id}")
    private String nodeId;

    @Value("${raftpay.grpc.port:9090}")
    private int grpcPort;

    @Value("${raftpay.peers:}")
    private String peersConfig; // format: "node2=host2:port2,node3=host3:port3"

    @Value("${raftpay.data.dir:./data}")
    private String dataDir;

    @Value("${raftpay.election.timeout.min:300}")
    private int electionTimeoutMin;

    @Value("${raftpay.election.timeout.max:500}")
    private int electionTimeoutMax;

    @Value("${raftpay.heartbeat.interval:100}")
    private int heartbeatInterval;

    private RaftNode raftNode;

    public static void main(String[] args) {
        SpringApplication.run(RaftPayApplication.class, args);
    }

    @Bean
    public BankStateMachine bankStateMachine() {
        return new BankStateMachine();
    }

    @Bean
    public RaftNode raftNode(BankStateMachine stateMachine) throws IOException {
        Map<String, String> peers = parsePeers(peersConfig);

        RaftConfig config = RaftConfig.builder()
                .nodeId(nodeId)
                .grpcPort(grpcPort)
                .peers(peers)
                .electionTimeoutMin(electionTimeoutMin)
                .electionTimeoutMax(electionTimeoutMax)
                .heartbeatInterval(heartbeatInterval)
                .dataDir(dataDir + "/" + nodeId)
                .build();

        raftNode = new RaftNode(config, stateMachine);
        raftNode.start();

        log.info("RaftPay node '{}' started with {} peers", nodeId, peers.size());
        return raftNode;
    }

    @PreDestroy
    public void shutdown() {
        if (raftNode != null) {
            raftNode.stop();
        }
    }

    private Map<String, String> parsePeers(String peersStr) {
        Map<String, String> peers = new HashMap<>();
        if (peersStr == null || peersStr.isBlank()) return peers;

        for (String peerEntry : peersStr.split(",")) {
            peerEntry = peerEntry.trim();
            if (peerEntry.isEmpty()) continue;
            String[] parts = peerEntry.split("=", 2);
            if (parts.length == 2) {
                peers.put(parts[0].trim(), parts[1].trim());
            }
        }
        return peers;
    }
}
