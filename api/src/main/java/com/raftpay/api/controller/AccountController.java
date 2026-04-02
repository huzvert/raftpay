package com.raftpay.api.controller;

import com.raftpay.api.dto.*;
import com.raftpay.api.service.RaftService;
import com.raftpay.banking.Account;
import com.raftpay.banking.BankStateMachine;
import com.raftpay.banking.CommandResult;
import com.raftpay.raft.RaftNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/api/accounts")
@CrossOrigin(origins = "*")
public class AccountController {

    private final RaftService raftService;
    private final BankStateMachine bankStateMachine;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${raftpay.peers:}")
    private String peersConfig;

    public AccountController(RaftService raftService, BankStateMachine bankStateMachine) {
        this.raftService = raftService;
        this.bankStateMachine = bankStateMachine;
    }

    /** Resolve a nodeId (e.g. "node2") to its internal HTTP URL */
    private String resolveLeaderUrl(String leaderId) {
        if (peersConfig == null || peersConfig.isBlank()) return null;
        for (String entry : peersConfig.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length == 2 && parts[0].trim().equals(leaderId)) {
                String hostname = parts[1].trim().split(":")[0];
                return "http://" + hostname + ":8080";
            }
        }
        return null;
    }

    /** Forward a POST request to the leader node */
    private ResponseEntity<ApiResponse> forwardToLeader(String leaderId, String path, Object body) {
        String leaderUrl = resolveLeaderUrl(leaderId);
        if (leaderUrl == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Cannot resolve leader: " + leaderId));
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    leaderUrl + path, HttpMethod.POST, entity, ApiResponse.class);
            return response;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Failed to forward to leader: " + e.getMessage()));
        }
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
            return forwardToLeader(e.getLeaderId(), "/api/accounts", request);
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
            return forwardToLeader(e.getLeaderId(), "/api/accounts/" + accountId + "/deposit", request);
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
            return forwardToLeader(e.getLeaderId(), "/api/accounts/" + accountId + "/withdraw", request);
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
