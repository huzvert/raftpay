package com.raftpay.banking;

import com.raftpay.raft.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Banking state machine that processes financial commands replicated through Raft.
 * Maintains account balances and ensures idempotent execution.
 */
public class BankStateMachine implements StateMachine {

    private static final Logger log = LoggerFactory.getLogger(BankStateMachine.class);

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();

    @Override
    public byte[] apply(byte[] commandBytes) {
        try {
            Command cmd = Command.fromBytes(commandBytes);

            // Idempotency check
            if (cmd.getIdempotencyKey() != null && !cmd.getIdempotencyKey().isEmpty()) {
                if (!processedKeys.add(cmd.getIdempotencyKey())) {
                    log.debug("Skipping duplicate command: {}", cmd.getIdempotencyKey());
                    return CommandResult.success("Already processed (idempotent)", cmd.getAccountId(), null).toBytes();
                }
            }

            CommandResult result = switch (cmd.getType()) {
                case CREATE_ACCOUNT -> createAccount(cmd);
                case DEPOSIT -> deposit(cmd);
                case WITHDRAW -> withdraw(cmd);
                case TRANSFER -> transfer(cmd);
            };

            log.debug("Applied command: {} -> {}", cmd, result.getMessage());
            return result.toBytes();

        } catch (Exception e) {
            log.error("Error applying command", e);
            return CommandResult.failure("Internal error: " + e.getMessage()).toBytes();
        }
    }

    private CommandResult createAccount(Command cmd) {
        if (accounts.containsKey(cmd.getAccountId())) {
            return CommandResult.failure("Account already exists: " + cmd.getAccountId());
        }

        Account account = new Account(cmd.getAccountId(), cmd.getOwnerName(), cmd.getInitialBalanceCents());
        accounts.put(cmd.getAccountId(), account);

        return CommandResult.success(
                "Account created: " + cmd.getAccountId(),
                cmd.getAccountId(),
                account.getBalanceCents()
        );
    }

    private CommandResult deposit(Command cmd) {
        Account account = accounts.get(cmd.getAccountId());
        if (account == null) {
            return CommandResult.failure("Account not found: " + cmd.getAccountId());
        }

        account.deposit(cmd.getAmountCents());

        return CommandResult.success(
                String.format("Deposited $%.2f to %s", cmd.getAmountCents() / 100.0, cmd.getAccountId()),
                cmd.getAccountId(),
                account.getBalanceCents()
        );
    }

    private CommandResult withdraw(Command cmd) {
        Account account = accounts.get(cmd.getAccountId());
        if (account == null) {
            return CommandResult.failure("Account not found: " + cmd.getAccountId());
        }

        try {
            account.withdraw(cmd.getAmountCents());
        } catch (Account.InsufficientFundsException e) {
            return CommandResult.failure(e.getMessage());
        }

        return CommandResult.success(
                String.format("Withdrew $%.2f from %s", cmd.getAmountCents() / 100.0, cmd.getAccountId()),
                cmd.getAccountId(),
                account.getBalanceCents()
        );
    }

    private CommandResult transfer(Command cmd) {
        Account from = accounts.get(cmd.getAccountId());
        Account to = accounts.get(cmd.getToAccountId());

        if (from == null) return CommandResult.failure("Source account not found: " + cmd.getAccountId());
        if (to == null) return CommandResult.failure("Destination account not found: " + cmd.getToAccountId());
        if (cmd.getAccountId().equals(cmd.getToAccountId())) return CommandResult.failure("Cannot transfer to same account");

        try {
            from.withdraw(cmd.getAmountCents());
            to.deposit(cmd.getAmountCents());
        } catch (Account.InsufficientFundsException e) {
            return CommandResult.failure(e.getMessage());
        }

        return CommandResult.success(
                String.format("Transferred $%.2f from %s to %s",
                        cmd.getAmountCents() / 100.0, cmd.getAccountId(), cmd.getToAccountId()),
                cmd.getAccountId(),
                from.getBalanceCents()
        );
    }

    // ========== Query Methods (read directly, no Raft needed) ==========

    public Account getAccount(String accountId) {
        return accounts.get(accountId);
    }

    public Collection<Account> getAllAccounts() {
        return Collections.unmodifiableCollection(accounts.values());
    }

    public int getAccountCount() {
        return accounts.size();
    }
}
