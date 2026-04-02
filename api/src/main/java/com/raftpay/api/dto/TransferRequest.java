package com.raftpay.api.dto;

public class TransferRequest {
    private String fromAccountId;
    private String toAccountId;
    private double amount; // in dollars

    public String getFromAccountId() { return fromAccountId; }
    public void setFromAccountId(String fromAccountId) { this.fromAccountId = fromAccountId; }
    public String getToAccountId() { return toAccountId; }
    public void setToAccountId(String toAccountId) { this.toAccountId = toAccountId; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}
