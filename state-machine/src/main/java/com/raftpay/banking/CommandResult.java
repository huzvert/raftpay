package com.raftpay.banking;

import com.google.gson.Gson;

/**
 * Result of applying a banking command.
 */
public class CommandResult {

    private static final Gson GSON = new Gson();

    private boolean success;
    private String message;
    private String accountId;
    private Long balanceCents;

    public CommandResult() {}

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getAccountId() { return accountId; }
    public Long getBalanceCents() { return balanceCents; }

    public byte[] toBytes() {
        return GSON.toJson(this).getBytes();
    }

    public static CommandResult fromBytes(byte[] bytes) {
        return GSON.fromJson(new String(bytes), CommandResult.class);
    }

    public static CommandResult success(String message, String accountId, Long balanceCents) {
        CommandResult result = new CommandResult();
        result.success = true;
        result.message = message;
        result.accountId = accountId;
        result.balanceCents = balanceCents;
        return result;
    }

    public static CommandResult failure(String message) {
        CommandResult result = new CommandResult();
        result.success = false;
        result.message = message;
        return result;
    }
}
