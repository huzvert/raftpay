package com.raftpay.banking;

/**
 * Bank account with balance stored in cents to avoid floating-point issues.
 */
public class Account {

    private final String accountId;
    private final String ownerName;
    private long balanceCents; // stored in cents: $100.50 = 10050

    public Account(String accountId, String ownerName, long balanceCents) {
        this.accountId = accountId;
        this.ownerName = ownerName;
        this.balanceCents = balanceCents;
    }

    public String getAccountId() { return accountId; }
    public String getOwnerName() { return ownerName; }
    public long getBalanceCents() { return balanceCents; }

    public double getBalanceDollars() {
        return balanceCents / 100.0;
    }

    public void deposit(long amountCents) {
        if (amountCents <= 0) throw new IllegalArgumentException("Deposit amount must be positive");
        this.balanceCents += amountCents;
    }

    public void withdraw(long amountCents) {
        if (amountCents <= 0) throw new IllegalArgumentException("Withdrawal amount must be positive");
        if (amountCents > balanceCents) throw new InsufficientFundsException(accountId, balanceCents, amountCents);
        this.balanceCents -= amountCents;
    }

    @Override
    public String toString() {
        return String.format("Account{id='%s', owner='%s', balance=$%.2f}", accountId, ownerName, getBalanceDollars());
    }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String accountId, long balance, long requested) {
            super(String.format("Insufficient funds in account %s: balance=$%.2f, requested=$%.2f",
                    accountId, balance / 100.0, requested / 100.0));
        }
    }
}
