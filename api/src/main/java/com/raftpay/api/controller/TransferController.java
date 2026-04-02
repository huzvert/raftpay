package com.raftpay.api.controller;

import com.raftpay.api.dto.ApiResponse;
import com.raftpay.api.dto.TransferRequest;
import com.raftpay.api.service.RaftService;
import com.raftpay.banking.CommandResult;
import com.raftpay.raft.RaftNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/transfers")
@CrossOrigin(origins = "*")
public class TransferController {

    private final RaftService raftService;

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
            if (e.getLeaderId() != null) {
                return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                        .header("X-Raft-Leader", e.getLeaderId())
                        .body(ApiResponse.error("Not the leader. Redirect to: " + e.getLeaderId()));
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("No leader elected."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Service unavailable: " + e.getMessage()));
        }
    }
}
