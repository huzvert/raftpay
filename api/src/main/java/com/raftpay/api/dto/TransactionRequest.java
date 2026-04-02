package com.raftpay.api.dto;

public class TransactionRequest {
    private double amount; // in dollars

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}
