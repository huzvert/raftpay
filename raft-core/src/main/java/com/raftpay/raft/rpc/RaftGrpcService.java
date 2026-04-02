package com.raftpay.raft.rpc;

import com.raftpay.proto.*;
import com.raftpay.raft.RaftNode;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server implementation for Raft RPCs.
 * Delegates all logic to the RaftNode.
 */
public class RaftGrpcService extends RaftServiceGrpc.RaftServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(RaftGrpcService.class);
    private final RaftNode raftNode;

    public RaftGrpcService(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        try {
            VoteResponse response = raftNode.handleVoteRequest(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error handling RequestVote from {}", request.getCandidateId(), e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
        try {
            AppendEntriesResponse response = raftNode.handleAppendEntries(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error handling AppendEntries from {}", request.getLeaderId(), e);
            responseObserver.onError(e);
        }
    }
}
