package com.raftpay.api.controller;

import com.raftpay.api.dto.*;
import com.raftpay.api.service.RaftService;
import com.raftpay.banking.Account;
import com.raftpay.banking.BankStateMachine;
import com.raftpay.banking.CommandResult;
import com.raftpay.raft.RaftNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/accounts")
@CrossOrigin(origins = "*")
public class AccountController {

    private final RaftService raftService;
    private final BankStateMachine bankStateMachine;

    public AccountController(RaftService raftService, BankStateMachine bankStateMachine) {
        this.raftService = raftService;
        this.bankStateMachine = bankStateMachine;
    }

    @PostMapping
    public ResponseEntity<ApiResponse> createAccount(@RequestBody CreateAccountRequest request) {
        try {
            long initialBalanceCents = Math.round(request.getInitialBalance() * 100);
            CommandResult result = raftService.createAccount(
                    request.getAccountId(),
                    request.getOwnerName(),
                    initialBalanceCents
            );

            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.ok(result.getMessage(), Map.of(
                        "accountId", request.getAccountId(),
                        "balance", request.getInitialBalance()
                )));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.error(result.getMessage()));
            }
        } catch (RaftNode.NotLeaderException e) {
            return redirectToLeader(e.getLeaderId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Service unavailable: " + e.getMessage()));
        }
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<ApiResponse> getAccount(@PathVariable String accountId) {
        Account account = bankStateMachine.getAccount(accountId);
        if (account == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", account.getAccountId());
        data.put("ownerName", account.getOwnerName());
        data.put("balance", account.getBalanceDollars());
        data.put("balanceCents", account.getBalanceCents());

        return ResponseEntity.ok(ApiResponse.ok("Account found", data));
    }

    @GetMapping
    public ResponseEntity<ApiResponse> getAllAccounts() {
        List<Map<String, Object>> accountList = new ArrayList<>();
        for (Account account : bankStateMachine.getAllAccounts()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", account.getAccountId());
            data.put("ownerName", account.getOwnerName());
            data.put("balance", account.getBalanceDollars());
            accountList.add(data);
        }
        return ResponseEntity.ok(ApiResponse.ok("Found " + accountList.size() + " accounts", accountList));
    }

    @PostMapping("/{accountId}/deposit")
    public ResponseEntity<ApiResponse> deposit(@PathVariable String accountId, @RequestBody TransactionRequest request) {
        try {
            long amountCents = Math.round(request.getAmount() * 100);
            CommandResult result = raftService.deposit(accountId, amountCents);

            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.ok(result.getMessage(), Map.of(
                        "accountId", accountId,
                        "newBalance", result.getBalanceCents() / 100.0
                )));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.error(result.getMessage()));
            }
        } catch (RaftNode.NotLeaderException e) {
            return redirectToLeader(e.getLeaderId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Service unavailable: " + e.getMessage()));
        }
    }

    @PostMapping("/{accountId}/withdraw")
    public ResponseEntity<ApiResponse> withdraw(@PathVariable String accountId, @RequestBody TransactionRequest request) {
        try {
            long amountCents = Math.round(request.getAmount() * 100);
            CommandResult result = raftService.withdraw(accountId, amountCents);

            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.ok(result.getMessage(), Map.of(
                        "accountId", accountId,
                        "newBalance", result.getBalanceCents() / 100.0
                )));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.error(result.getMessage()));
            }
        } catch (RaftNode.NotLeaderException e) {
            return redirectToLeader(e.getLeaderId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Service unavailable: " + e.getMessage()));
        }
    }

    private ResponseEntity<ApiResponse> redirectToLeader(String leaderId) {
        if (leaderId != null) {
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .header("X-Raft-Leader", leaderId)
                    .body(ApiResponse.error("Not the leader. Redirect to: " + leaderId));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("No leader elected. Cluster may be unavailable."));
    }
}
