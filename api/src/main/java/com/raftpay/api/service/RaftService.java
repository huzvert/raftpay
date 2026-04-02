package com.raftpay.api.service;

import com.raftpay.banking.Command;
import com.raftpay.banking.CommandResult;
import com.raftpay.raft.RaftNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Bridge between REST API and Raft consensus.
 * Serializes commands, proposes them through Raft, and returns results.
 */
@Service
public class RaftService {

    private static final Logger log = LoggerFactory.getLogger(RaftService.class);
    private static final long PROPOSAL_TIMEOUT_MS = 5000;

    private final RaftNode raftNode;

    public RaftService(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    public CommandResult createAccount(String accountId, String ownerName, long initialBalanceCents) throws Exception {
        Command cmd = Command.createAccount(accountId, ownerName, initialBalanceCents);
        return propose(cmd);
    }

    public CommandResult deposit(String accountId, long amountCents) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        Command cmd = Command.deposit(accountId, amountCents, idempotencyKey);
        return propose(cmd);
    }

    public CommandResult withdraw(String accountId, long amountCents) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        Command cmd = Command.withdraw(accountId, amountCents, idempotencyKey);
        return propose(cmd);
    }

    public CommandResult transfer(String fromAccountId, String toAccountId, long amountCents) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        Command cmd = Command.transfer(fromAccountId, toAccountId, amountCents, idempotencyKey);
        return propose(cmd);
    }

    private CommandResult propose(Command cmd) throws Exception {
        log.info("Proposing command: {}", cmd);
        byte[] resultBytes = raftNode.propose(cmd.toBytes(), PROPOSAL_TIMEOUT_MS);
        return CommandResult.fromBytes(resultBytes);
    }

    public RaftNode getRaftNode() {
        return raftNode;
    }
}
