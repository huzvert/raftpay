package com.raftpay.raft.rpc;

import com.raftpay.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client for sending Raft RPCs to peer nodes.
 * Maintains a channel pool and handles connection failures gracefully.
 */
public class RaftGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(RaftGrpcClient.class);
    private static final int RPC_TIMEOUT_MS = 2000;

    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, RaftServiceGrpc.RaftServiceBlockingStub> stubs = new ConcurrentHashMap<>();
    private final Map<String, String> peerAddresses; // peerId -> "host:port"

    public RaftGrpcClient(Map<String, String> peerAddresses) {
        this.peerAddresses = peerAddresses;
    }

    private RaftServiceGrpc.RaftServiceBlockingStub getStub(String peerId) {
        return stubs.computeIfAbsent(peerId, id -> {
            String address = peerAddresses.get(id);
            if (address == null) {
                throw new IllegalArgumentException("Unknown peer: " + id);
            }
            String[] parts = address.split(":");
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(parts[0], Integer.parseInt(parts[1]))
                    .usePlaintext()
                    .keepAliveTime(10, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .build();
            channels.put(id, channel);
            return RaftServiceGrpc.newBlockingStub(channel);
        });
    }

    public VoteResponse requestVote(String peerId, VoteRequest request) {
        try {
            return getStub(peerId)
                    .withDeadlineAfter(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .requestVote(request);
        } catch (StatusRuntimeException e) {
            log.debug("RequestVote to {} failed: {}", peerId, e.getStatus());
            if (shouldInvalidate(e.getStatus())) {
                invalidateStub(peerId);
            }
            return null;
        } catch (Exception e) {
            log.debug("RequestVote to {} failed: {}", peerId, e.getMessage());
            invalidateStub(peerId);
            return null;
        }
    }

    public AppendEntriesResponse appendEntries(String peerId, AppendEntriesRequest request) {
        try {
            return getStub(peerId)
                    .withDeadlineAfter(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .appendEntries(request);
        } catch (StatusRuntimeException e) {
            log.debug("AppendEntries to {} failed: {}", peerId, e.getStatus());
            if (shouldInvalidate(e.getStatus())) {
                invalidateStub(peerId);
            }
            return null;
        } catch (Exception e) {
            log.debug("AppendEntries to {} failed: {}", peerId, e.getMessage());
            invalidateStub(peerId);
            return null;
        }
    }

    private boolean shouldInvalidate(Status status) {
        // Only invalidate on permanent connection errors, not transient ones
        Status.Code code = status.getCode();
        return code == Status.Code.UNAVAILABLE
                || code == Status.Code.UNIMPLEMENTED
                || code == Status.Code.INTERNAL;
    }

    private void invalidateStub(String peerId) {
        stubs.remove(peerId);
        ManagedChannel channel = channels.remove(peerId);
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    public void shutdown() {
        for (ManagedChannel channel : channels.values()) {
            try {
                channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        channels.clear();
        stubs.clear();
    }
}
