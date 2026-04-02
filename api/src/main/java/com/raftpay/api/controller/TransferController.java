package com.raftpay.api.controller;

import com.raftpay.api.dto.ApiResponse;
import com.raftpay.api.dto.TransferRequest;
import com.raftpay.api.service.RaftService;
import com.raftpay.banking.CommandResult;
import com.raftpay.raft.RaftNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/api/transfers")
@CrossOrigin(origins = "*")
public class TransferController {

    private final RaftService raftService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${raftpay.peers:}")
    private String peersConfig;

    public TransferController(RaftService raftService) {
        this.raftService = raftService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse> transfer(@RequestBody TransferRequest request) {
        try {
            long amountCents = Math.round(request.getAmount() * 100);
            CommandResult result = raftService.transfer(
                    request.getFromAccountId(),
                    request.getToAccountId(),
                    amountCents
            );

            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.ok(result.getMessage(), Map.of(
                        "from", request.getFromAccountId(),
                        "to", request.getToAccountId(),
                        "amount", request.getAmount()
                )));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.error(result.getMessage()));
            }
        } catch (RaftNode.NotLeaderException e) {
            return forwardToLeader(e.getLeaderId(), request);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Service unavailable: " + e.getMessage()));
        }
    }

    private ResponseEntity<ApiResponse> forwardToLeader(String leaderId, Object body) {
        if (leaderId == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("No leader elected."));
        }
        String leaderUrl = resolveLeaderUrl(leaderId);
        if (leaderUrl == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Cannot resolve leader: " + leaderId));
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            return restTemplate.exchange(
                    leaderUrl + "/api/transfers", HttpMethod.POST, entity, ApiResponse.class);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Failed to forward to leader: " + ex.getMessage()));
        }
    }

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
}
