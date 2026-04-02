package com.raftpay.banking;

import com.google.gson.Gson;

/**
 * Represents a banking command that gets replicated through Raft.
 */
public class Command {

    private static final Gson GSON = new Gson();

    public enum Type {
        CREATE_ACCOUNT,
        DEPOSIT,
        WITHDRAW,
        TRANSFER
    }

    private Type type;
    private String accountId;
    private String ownerName;
    private String toAccountId;
    private long amountCents;
    private long initialBalanceCents;
    private String idempotencyKey;

    // Required for Gson deserialization
    public Command() {}

    public Type getType() { return type; }
    public String getAccountId() { return accountId; }
    public String getOwnerName() { return ownerName; }
    public String getToAccountId() { return toAccountId; }
    public long getAmountCents() { return amountCents; }
    public long getInitialBalanceCents() { return initialBalanceCents; }
    public String getIdempotencyKey() { return idempotencyKey; }

    // Serialization
    public byte[] toBytes() {
        return GSON.toJson(this).getBytes();
    }

    public static Command fromBytes(byte[] bytes) {
        return GSON.fromJson(new String(bytes), Command.class);
    }

    // Factory methods
    public static Command createAccount(String accountId, String ownerName, long initialBalanceCents) {
        Command cmd = new Command();
        cmd.type = Type.CREATE_ACCOUNT;
        cmd.accountId = accountId;
        cmd.ownerName = ownerName;
        cmd.initialBalanceCents = initialBalanceCents;
        return cmd;
    }

    public static Command deposit(String accountId, long amountCents, String idempotencyKey) {
        Command cmd = new Command();
        cmd.type = Type.DEPOSIT;
        cmd.accountId = accountId;
        cmd.amountCents = amountCents;
        cmd.idempotencyKey = idempotencyKey;
        return cmd;
    }

    public static Command withdraw(String accountId, long amountCents, String idempotencyKey) {
        Command cmd = new Command();
        cmd.type = Type.WITHDRAW;
        cmd.accountId = accountId;
        cmd.amountCents = amountCents;
        cmd.idempotencyKey = idempotencyKey;
        return cmd;
    }

    public static Command transfer(String fromAccountId, String toAccountId, long amountCents, String idempotencyKey) {
        Command cmd = new Command();
        cmd.type = Type.TRANSFER;
        cmd.accountId = fromAccountId;
        cmd.toAccountId = toAccountId;
        cmd.amountCents = amountCents;
        cmd.idempotencyKey = idempotencyKey;
        return cmd;
    }

    @Override
    public String toString() {
        return switch (type) {
            case CREATE_ACCOUNT -> String.format("CREATE_ACCOUNT(%s, %s, $%.2f)", accountId, ownerName, initialBalanceCents / 100.0);
            case DEPOSIT -> String.format("DEPOSIT(%s, $%.2f)", accountId, amountCents / 100.0);
            case WITHDRAW -> String.format("WITHDRAW(%s, $%.2f)", accountId, amountCents / 100.0);
            case TRANSFER -> String.format("TRANSFER(%s -> %s, $%.2f)", accountId, toAccountId, amountCents / 100.0);
        };
    }
}
