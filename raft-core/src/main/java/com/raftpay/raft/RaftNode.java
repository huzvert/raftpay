package com.raftpay.raft;

import com.raftpay.proto.*;
import com.raftpay.raft.election.ElectionTimer;
import com.raftpay.raft.rpc.RaftGrpcClient;
import com.raftpay.raft.rpc.RaftGrpcService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core Raft consensus node.
 * Implements leader election, log replication, and commitment.
 */
public class RaftNode {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    // ========== Configuration ==========
    private final RaftConfig config;
    private final String nodeId;

    // ========== Persistent State (survives restart) ==========
    private long currentTerm = 0;
    private String votedFor = null;
    private final RaftLog raftLog = new RaftLog();
    private final PersistentState persistence;

    // ========== Volatile State (all nodes) ==========
    private volatile RaftState state = RaftState.FOLLOWER;
    private volatile String leaderId = null;
    private final AtomicLong commitIndex = new AtomicLong(0);
    private final AtomicLong lastApplied = new AtomicLong(0);
    private final AtomicLong lastHeartbeatTime = new AtomicLong(0); // tracks when we last heard from a valid leader

    // ========== Volatile State (leader only, reset on election) ==========
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();

    // ========== Components ==========
    private final ElectionTimer electionTimer;
    private final RaftGrpcClient grpcClient;
    private Server grpcServer;
    private StateMachine stateMachine;

    // ========== Threading ==========
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final ExecutorService rpcExecutor = Executors.newCachedThreadPool();
    private ScheduledFuture<?> heartbeatTask;

    // ========== Client proposal tracking ==========
    // Maps log index -> CompletableFuture that completes when the entry is committed
    private final Map<Long, CompletableFuture<byte[]>> pendingProposals = new ConcurrentHashMap<>();

    // Lock for state mutations
    private final Object stateLock = new Object();

    public RaftNode(RaftConfig config, StateMachine stateMachine) {
        this.config = config;
        this.nodeId = config.getNodeId();
        this.stateMachine = stateMachine;
        this.persistence = new PersistentState(config.getDataDir());
        this.grpcClient = new RaftGrpcClient(config.getPeers());
        this.electionTimer = new ElectionTimer(
                config.getElectionTimeoutMin(),
                config.getElectionTimeoutMax(),
                this::startElection
        );

        // Restore persisted state if available
        restoreState();
    }

    // ========== Lifecycle ==========

    public void start() throws IOException {
        // Start gRPC server
        // Server-side keepalive policy: permit client pings every 5s without active calls.
        // Without this, the default policy rejects pings as "too_many_pings" (GOAWAY
        // ENHANCE_YOUR_CALM), killing connections and causing heartbeat gaps that
        // trigger spurious elections.
        grpcServer = NettyServerBuilder.forPort(config.getGrpcPort())
                .addService(new RaftGrpcService(this))
                .permitKeepAliveTime(5, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .build()
                .start();
        log.info("[{}] gRPC server started on port {}", nodeId, config.getGrpcPort());

        // Start election timer (all nodes start as followers)
        electionTimer.start();

        // Start applier thread
        scheduler.scheduleWithFixedDelay(this::applyCommitted, 10, 10, TimeUnit.MILLISECONDS);

        log.info("[{}] Raft node started (cluster size: {}, majority: {})",
                nodeId, config.getClusterSize(), config.getMajority());
    }

    public void stop() {
        electionTimer.stop();
        stopHeartbeat();
        scheduler.shutdownNow();
        rpcExecutor.shutdownNow();
        grpcClient.shutdown();
        if (grpcServer != null) {
            grpcServer.shutdownNow();
        }
        log.info("[{}] Raft node stopped", nodeId);
    }

    // ========== Leader Election ==========

    private void startElection() {
        synchronized (stateLock) {
            // Leader lease: don't start election if we recently heard from a leader.
            // Prevents spurious elections from disrupting a stable cluster (Raft §6).
            long lastHB = lastHeartbeatTime.get();
            long timeSinceHeartbeat = System.currentTimeMillis() - lastHB;
            if (lastHB > 0 && timeSinceHeartbeat < config.getElectionTimeoutMin()) {
                log.debug("[{}] Suppressing election - last heartbeat was {}ms ago", nodeId, timeSinceHeartbeat);
                return;
            }
        }

        // Pre-vote phase (Raft §9.6): ask peers if they would vote for us at term+1
        // WITHOUT incrementing our term yet. If a majority would not grant, abort —
        // this prevents term inflation from a partitioned/disconnected node from
        // disrupting a stable cluster when it rejoins.
        if (!runPreVote()) {
            log.debug("[{}] Pre-vote did not pass — aborting election to avoid term inflation", nodeId);
            electionTimer.reset();
            return;
        }

        synchronized (stateLock) {
            currentTerm++;
            state = RaftState.CANDIDATE;
            votedFor = nodeId;
            leaderId = null;
            persistState();
            log.info("[{}] Pre-vote granted — starting real election for term {}", nodeId, currentTerm);
        }

        electionTimer.reset();

        final long electionTerm;
        final long lastLogIndex;
        final long lastLogTerm;

        synchronized (stateLock) {
            electionTerm = currentTerm;
            lastLogIndex = raftLog.getLastIndex();
            lastLogTerm = raftLog.getLastTerm();
        }

        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(electionTerm)
                .setCandidateId(nodeId)
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        // Vote for self = 1
        int votesReceived = 1;
        int majority = config.getMajority();

        // Send RequestVote to all peers in parallel
        List<Future<VoteResponse>> futures = new ArrayList<>();
        for (String peerId : config.getPeers().keySet()) {
            futures.add(rpcExecutor.submit(() -> grpcClient.requestVote(peerId, request)));
        }

        // Collect votes
        for (Future<VoteResponse> future : futures) {
            try {
                VoteResponse response = future.get(1, TimeUnit.SECONDS);
                if (response == null) continue;

                synchronized (stateLock) {
                    // Check if we've moved on (term changed or no longer candidate)
                    if (currentTerm != electionTerm || state != RaftState.CANDIDATE) {
                        return;
                    }

                    if (response.getTerm() > currentTerm) {
                        stepDown(response.getTerm());
                        return;
                    }

                    if (response.getVoteGranted()) {
                        votesReceived++;
                    }
                }

                if (votesReceived >= majority) {
                    break;
                }
            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                // Peer unreachable, continue
            }
        }

        // Check if we won
        synchronized (stateLock) {
            if (currentTerm != electionTerm || state != RaftState.CANDIDATE) {
                return;
            }

            if (votesReceived >= majority) {
                becomeLeader();
            }
        }
    }

    /**
     * Pre-vote phase (Raft §9.6).
     * Asks peers whether they would grant a vote at currentTerm+1, without
     * actually incrementing our term or changing votedFor. Returns true if a
     * majority (including self) would grant. This prevents a disconnected
     * follower's runaway term from forcing a healthy leader to step down.
     */
    private boolean runPreVote() {
        final long hypotheticalTerm;
        final long lastLogIndex;
        final long lastLogTerm;

        synchronized (stateLock) {
            if (state == RaftState.LEADER) return false;
            hypotheticalTerm = currentTerm + 1;
            lastLogIndex = raftLog.getLastIndex();
            lastLogTerm = raftLog.getLastTerm();
        }

        log.info("[{}] Starting pre-vote for hypothetical term {}", nodeId, hypotheticalTerm);

        VoteRequest request = VoteRequest.newBuilder()
                .setTerm(hypotheticalTerm)
                .setCandidateId(nodeId)
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        int votesReceived = 1; // self
        int majority = config.getMajority();

        List<Future<VoteResponse>> futures = new ArrayList<>();
        for (String peerId : config.getPeers().keySet()) {
            futures.add(rpcExecutor.submit(() -> grpcClient.preVote(peerId, request)));
        }

        for (Future<VoteResponse> future : futures) {
            try {
                VoteResponse response = future.get(1, TimeUnit.SECONDS);
                if (response == null) continue;
                if (response.getVoteGranted()) {
                    votesReceived++;
                }
            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                // peer unreachable — counts as "no"
            }
            if (votesReceived >= majority) break;
        }

        boolean passed = votesReceived >= majority;
        log.info("[{}] Pre-vote {}: {}/{}", nodeId, passed ? "PASSED" : "FAILED", votesReceived, majority);
        return passed;
    }

    /**
     * Handles incoming PreVote RPCs. CRITICAL: must not mutate any state
     * (no term update, no votedFor change, no timer reset). It is purely
     * a read-only "would I vote?" check.
     */
    public VoteResponse handlePreVoteRequest(VoteRequest request) {
        synchronized (stateLock) {
            // Leader lease: if we recently heard from a valid leader, deny.
            long now = System.currentTimeMillis();
            long lastHB = lastHeartbeatTime.get();
            long timeSinceHeartbeat = now - lastHB;
            if (lastHB > 0 && timeSinceHeartbeat < config.getElectionTimeoutMin()) {
                log.debug("[{}] Denying pre-vote for {} (leader lease, {}ms ago)",
                        nodeId, request.getCandidateId(), timeSinceHeartbeat);
                return VoteResponse.newBuilder()
                        .setTerm(currentTerm)
                        .setVoteGranted(false)
                        .build();
            }

            // Hypothetical term must be at least our current term.
            if (request.getTerm() < currentTerm) {
                return VoteResponse.newBuilder()
                        .setTerm(currentTerm)
                        .setVoteGranted(false)
                        .build();
            }

            // Log must be at least as up-to-date as ours.
            boolean granted = isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm());

            return VoteResponse.newBuilder()
                    .setTerm(currentTerm)
                    .setVoteGranted(granted)
                    .build();
        }
    }

    private void becomeLeader() {
        state = RaftState.LEADER;
        leaderId = nodeId;
        lastHeartbeatTime.updateAndGet(old -> Math.max(old, System.currentTimeMillis())); // Protect leader
        log.info("[{}] Became LEADER for term {}", nodeId, currentTerm);

        // Initialize nextIndex and matchIndex for each peer
        long lastLogIdx = raftLog.getLastIndex();
        for (String peerId : config.getPeers().keySet()) {
            nextIndex.put(peerId, lastLogIdx + 1);
            matchIndex.put(peerId, 0L);
        }

        // Stop election timer (leaders don't need it)
        electionTimer.stop();

        // Start sending heartbeats
        startHeartbeat();

        // Send immediate heartbeat to establish authority
        sendHeartbeats();
    }

    private void stepDown(long newTerm) {
        log.info("[{}] Stepping down: term {} -> {}", nodeId, currentTerm, newTerm);
        currentTerm = newTerm;
        state = RaftState.FOLLOWER;
        votedFor = null;
        leaderId = null;
        persistState();
        stopHeartbeat();
        electionTimer.start();
        electionTimer.reset();

        // Fail any pending proposals
        failPendingProposals("Leadership lost");
    }

    // ========== RequestVote Handler ==========

    public VoteResponse handleVoteRequest(VoteRequest request) {
        synchronized (stateLock) {
            // Raft §6: If we recently heard from a valid leader, reject the vote request.
            // This prevents a rogue follower from disrupting a stable leader.
            long now = System.currentTimeMillis();
            long lastHB = lastHeartbeatTime.get();
            long timeSinceHeartbeat = now - lastHB;
            if (lastHB > 0 && timeSinceHeartbeat < config.getElectionTimeoutMin()) {
                log.debug("[{}] Rejecting vote for {} (leader lease, {}ms ago)",
                        nodeId, request.getCandidateId(), timeSinceHeartbeat);
                return VoteResponse.newBuilder()
                        .setTerm(currentTerm)
                        .setVoteGranted(false)
                        .build();
            }

            // If request term > currentTerm, step down
            if (request.getTerm() > currentTerm) {
                stepDown(request.getTerm());
            }

            boolean voteGranted = false;

            // Grant vote if:
            // 1. Request term >= currentTerm
            // 2. Haven't voted for someone else this term
            // 3. Candidate's log is at least as up-to-date as ours
            if (request.getTerm() >= currentTerm) {
                if (votedFor == null || votedFor.equals(request.getCandidateId())) {
                    if (isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm())) {
                        votedFor = request.getCandidateId();
                        voteGranted = true;
                        persistState();
                        electionTimer.reset();
                        log.debug("[{}] Voted for {} in term {}", nodeId, request.getCandidateId(), currentTerm);
                    }
                }
            }

            return VoteResponse.newBuilder()
                    .setTerm(currentTerm)
                    .setVoteGranted(voteGranted)
                    .build();
        }
    }

    /**
     * Raft's log comparison rule:
     * A log is "at least as up-to-date" if its last entry has a higher term,
     * or the same term but equal or greater index.
     */
    private boolean isLogUpToDate(long candidateLastIndex, long candidateLastTerm) {
        long myLastTerm = raftLog.getLastTerm();
        long myLastIndex = raftLog.getLastIndex();

        if (candidateLastTerm != myLastTerm) {
            return candidateLastTerm > myLastTerm;
        }
        return candidateLastIndex >= myLastIndex;
    }

    // ========== AppendEntries Handler ==========

    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        synchronized (stateLock) {
            // If request term > currentTerm, step down
            if (request.getTerm() > currentTerm) {
                stepDown(request.getTerm());
            }

            // Reject if stale term — but with an important exception:
            // If we're a candidate whose election hasn't succeeded (no entries committed
            // at our term), accept the lower-term leader's authority to prevent term
            // inflation from destabilizing the cluster. This is safe because no entries
            // have been committed at the candidate's inflated term.
            if (request.getTerm() < currentTerm) {
                // If we're a candidate or a follower with no known leader (our higher term
                // came from a failed election, not from an established leader), accept the
                // lower-term leader to prevent term inflation from destabilizing the cluster.
                if (state == RaftState.CANDIDATE || (state == RaftState.FOLLOWER && leaderId == null)) {
                    log.info("[{}] Accepting leader {} at term {} (reverting from inflated term {})",
                            nodeId, request.getLeaderId(), request.getTerm(), currentTerm);
                    currentTerm = request.getTerm();
                    state = RaftState.FOLLOWER;
                    votedFor = null;
                    persistState();
                    // Fall through to process the AppendEntries normally
                } else {
                    return AppendEntriesResponse.newBuilder()
                            .setTerm(currentTerm)
                            .setSuccess(false)
                            .build();
                }
            }

            // Valid AppendEntries from leader — reset election timer
            electionTimer.reset();
            lastHeartbeatTime.updateAndGet(old -> Math.max(old, System.currentTimeMillis()));
            leaderId = request.getLeaderId();

            // If we were a candidate, step down
            if (state == RaftState.CANDIDATE) {
                state = RaftState.FOLLOWER;
                votedFor = null;
            }
            state = RaftState.FOLLOWER;

            // Log consistency check
            if (request.getPrevLogIndex() > 0) {
                long prevLogTerm = raftLog.getTermAt(request.getPrevLogIndex());
                if (prevLogTerm == -1 || prevLogTerm != request.getPrevLogTerm()) {
                    // Log doesn't contain an entry at prevLogIndex with prevLogTerm
                    return AppendEntriesResponse.newBuilder()
                            .setTerm(currentTerm)
                            .setSuccess(false)
                            .build();
                }
            }

            // Append new entries (handle conflicts)
            if (!request.getEntriesList().isEmpty()) {
                for (LogEntryProto entryProto : request.getEntriesList()) {
                    long entryIndex = entryProto.getIndex();
                    long existingTerm = raftLog.getTermAt(entryIndex);

                    if (existingTerm == -1) {
                        // No entry at this index — append
                        raftLog.appendEntry(new LogEntry(
                                entryProto.getTerm(),
                                entryProto.getIndex(),
                                entryProto.getCommand().toByteArray()
                        ));
                    } else if (existingTerm != entryProto.getTerm()) {
                        // Conflict: delete this entry and all that follow
                        raftLog.truncateFrom(entryIndex);
                        raftLog.appendEntry(new LogEntry(
                                entryProto.getTerm(),
                                entryProto.getIndex(),
                                entryProto.getCommand().toByteArray()
                        ));
                    }
                    // If terms match, entry is already correct — skip
                }
                persistState();
            }

            // Update commit index
            if (request.getLeaderCommit() > commitIndex.get()) {
                long newCommitIndex = Math.min(request.getLeaderCommit(), raftLog.getLastIndex());
                commitIndex.set(newCommitIndex);
            }

            return AppendEntriesResponse.newBuilder()
                    .setTerm(currentTerm)
                    .setSuccess(true)
                    .build();
        }
    }

    // ========== Log Replication (Leader) ==========

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                0,
                config.getHeartbeatInterval(),
                TimeUnit.MILLISECONDS
        );
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private void sendHeartbeats() {
        if (state != RaftState.LEADER) return;

        // Update lastHeartbeatTime so the leader's own lease check protects it
        // from spurious VoteRequests while it's actively serving.
        long now = System.currentTimeMillis();
        lastHeartbeatTime.updateAndGet(old -> Math.max(old, now));

        for (String peerId : config.getPeers().keySet()) {
            rpcExecutor.submit(() -> replicateTo(peerId));
        }
    }

    private void replicateTo(String peerId) {
        if (state != RaftState.LEADER) return;

        long peerNextIndex = nextIndex.getOrDefault(peerId, 1L);
        long prevLogIndex = peerNextIndex - 1;
        long prevLogTerm = raftLog.getTermAt(prevLogIndex);

        List<LogEntry> entries = raftLog.getEntriesFrom(peerNextIndex);

        AppendEntriesRequest.Builder requestBuilder = AppendEntriesRequest.newBuilder()
                .setTerm(currentTerm)
                .setLeaderId(nodeId)
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm >= 0 ? prevLogTerm : 0)
                .setLeaderCommit(commitIndex.get());

        for (LogEntry entry : entries) {
            requestBuilder.addEntries(LogEntryProto.newBuilder()
                    .setTerm(entry.getTerm())
                    .setIndex(entry.getIndex())
                    .setCommand(com.google.protobuf.ByteString.copyFrom(entry.getCommand()))
                    .build());
        }

        AppendEntriesResponse response = grpcClient.appendEntries(peerId, requestBuilder.build());

        if (response == null) return; // Peer unreachable

        synchronized (stateLock) {
            if (response.getTerm() > currentTerm) {
                stepDown(response.getTerm());
                return;
            }

            if (state != RaftState.LEADER) return;

            if (response.getSuccess()) {
                // Update nextIndex and matchIndex
                if (!entries.isEmpty()) {
                    long lastReplicatedIndex = entries.get(entries.size() - 1).getIndex();
                    nextIndex.put(peerId, lastReplicatedIndex + 1);
                    matchIndex.put(peerId, lastReplicatedIndex);
                    // Check if we can advance commitIndex
                    advanceCommitIndex();
                }
            } else {
                // Log inconsistency — decrement nextIndex and retry
                long newNextIndex = Math.max(1, peerNextIndex - 1);
                nextIndex.put(peerId, newNextIndex);
                log.debug("[{}] AppendEntries to {} failed, backing up nextIndex to {}",
                        nodeId, peerId, newNextIndex);
            }
        }
    }

    /**
     * Advance commitIndex if there exists an N > commitIndex such that
     * a majority of matchIndex[i] >= N and log[N].term == currentTerm.
     */
    private void advanceCommitIndex() {
        long lastLogIndex = raftLog.getLastIndex();

        for (long n = lastLogIndex; n > commitIndex.get(); n--) {
            if (raftLog.getTermAt(n) != currentTerm) continue;

            // Count replicas (self + peers with matchIndex >= n)
            int replicaCount = 1; // self
            for (Long peerMatchIndex : matchIndex.values()) {
                if (peerMatchIndex >= n) {
                    replicaCount++;
                }
            }

            if (replicaCount >= config.getMajority()) {
                log.debug("[{}] Advancing commitIndex from {} to {}", nodeId, commitIndex.get(), n);
                commitIndex.set(n);
                break;
            }
        }
    }

    // ========== State Machine Application ==========

    private void applyCommitted() {
        while (lastApplied.get() < commitIndex.get()) {
            long indexToApply = lastApplied.get() + 1;
            LogEntry entry = raftLog.getEntry(indexToApply);
            if (entry == null) break;

            byte[] result = null;
            if (stateMachine != null && entry.getCommand().length > 0) {
                try {
                    result = stateMachine.apply(entry.getCommand());
                } catch (Exception e) {
                    log.error("[{}] Error applying entry at index {}", nodeId, indexToApply, e);
                }
            }

            lastApplied.set(indexToApply);

            // Complete pending proposal future (leader only)
            CompletableFuture<byte[]> future = pendingProposals.remove(indexToApply);
            if (future != null) {
                future.complete(result);
            }
        }
    }

    // ========== Client Proposal ==========

    /**
     * Propose a command to the Raft cluster.
     * Only the leader can accept proposals.
     * Blocks until the command is committed or times out.
     *
     * @param command serialized command bytes
     * @return serialized result from state machine
     * @throws IllegalStateException if this node is not the leader
     * @throws TimeoutException if the command is not committed within timeout
     */
    public byte[] propose(byte[] command, long timeoutMs) throws Exception {
        CompletableFuture<byte[]> future;

        synchronized (stateLock) {
            if (state != RaftState.LEADER) {
                throw new NotLeaderException(leaderId);
            }

            // Append to local log
            long index = raftLog.append(currentTerm, command);
            persistState();

            // Track the proposal
            future = new CompletableFuture<>();
            pendingProposals.put(index, future);
        }

        // Trigger immediate replication
        sendHeartbeats();

        // Wait for commit
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TimeoutException("Command not committed within " + timeoutMs + "ms");
        }
    }

    private void failPendingProposals(String reason) {
        for (Map.Entry<Long, CompletableFuture<byte[]>> entry : pendingProposals.entrySet()) {
            entry.getValue().completeExceptionally(new NotLeaderException(leaderId));
        }
        pendingProposals.clear();
    }

    // ========== Persistence ==========

    private void persistState() {
        persistence.save(currentTerm, votedFor, raftLog);
    }

    private void restoreState() {
        PersistentState.SavedState saved = persistence.load();
        if (saved != null) {
            this.currentTerm = saved.currentTerm();
            this.votedFor = saved.votedFor();
            this.raftLog.restore(saved.entries());
            log.info("[{}] Restored state: term={}, votedFor={}, logSize={}",
                    nodeId, currentTerm, votedFor, raftLog.size());
        }
    }

    // ========== Getters for API/Dashboard ==========

    public String getNodeId() { return nodeId; }
    public RaftState getState() { return state; }
    public long getCurrentTerm() { return currentTerm; }
    public String getLeaderId() { return leaderId; }
    public long getCommitIndex() { return commitIndex.get(); }
    public long getLastApplied() { return lastApplied.get(); }
    public long getLogSize() { return raftLog.size(); }
    public RaftLog getRaftLog() { return raftLog; }
    public RaftConfig getConfig() { return config; }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodeId", nodeId);
        status.put("state", state.name());
        status.put("currentTerm", currentTerm);
        status.put("leaderId", leaderId);
        status.put("commitIndex", commitIndex.get());
        status.put("lastApplied", lastApplied.get());
        status.put("logSize", raftLog.size());
        if (state == RaftState.LEADER) {
            status.put("nextIndex", new HashMap<>(nextIndex));
            status.put("matchIndex", new HashMap<>(matchIndex));
        }
        return status;
    }

    // ========== Exception ==========

    public static class NotLeaderException extends RuntimeException {
        private final String leaderId;

        public NotLeaderException(String leaderId) {
            super("Not the leader. Current leader: " + leaderId);
            this.leaderId = leaderId;
        }

        public String getLeaderId() {
            return leaderId;
        }
    }
}
