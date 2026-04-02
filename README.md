# RaftPay

A distributed payment system built from scratch on the **Raft consensus protocol**. Multiple nodes elect a leader, replicate transactions through a consensus log, and survive node failures without losing data.

## Architecture

```
                    ┌─────────────┐
                    │   Client    │
                    │ (Dashboard) │
                    └──────┬──────┘
                           │ REST API
              ┌────────────┼────────────┐
              │            │            │
        ┌─────▼─────┐ ┌───▼───┐ ┌─────▼─────┐
        │   Node 1   │ │Node 2 │ │   Node 3   │
        │ (Follower) │ │(Leader)│ │ (Follower) │
        └─────┬──────┘ └───┬───┘ └──────┬─────┘
              │            │             │
              └────────────┼─────────────┘
                      gRPC (Raft RPCs)
                   RequestVote, AppendEntries
```

Each node runs:
- **Raft Consensus Engine** — leader election, log replication, persistence
- **Banking State Machine** — accounts, deposits, withdrawals, transfers
- **REST API** — client-facing HTTP endpoints
- **gRPC Server** — inter-node Raft communication

## Features

- **Leader Election** — nodes automatically elect a leader using randomized timeouts
- **Log Replication** — all write operations are replicated to a majority before committing
- **Fault Tolerance** — cluster continues operating with 1 node down (2/3 majority)
- **Automatic Recovery** — failed nodes rejoin and catch up via log replication
- **Leader Forwarding** — write requests to followers are automatically forwarded to the leader
- **Live Dashboard** — real-time web UI showing cluster state, accounts, and Raft log
- **Remote Access** — dashboard works via ngrok for remote demos

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.4 |
| Consensus | Raft (custom implementation) |
| Inter-node RPC | gRPC + Protocol Buffers |
| Build | Gradle 8.12 |
| Deployment | Docker Compose |
| Dashboard | Vanilla HTML/CSS/JS |

## Project Structure

```
raftpay/
├── raft-core/          # Raft consensus engine
│   ├── RaftNode.java       # Core node (election, replication, commit)
│   ├── ElectionTimer.java  # Randomized election timeout
│   ├── RaftGrpcClient.java # gRPC client for peer communication
│   └── RaftLog.java        # Append-only log with persistence
├── state-machine/      # Banking state machine
│   ├── BankStateMachine.java  # Account operations
│   ├── Command.java           # Serializable commands
│   └── Account.java           # Account model
├── api/                # REST API + Dashboard
│   ├── controller/         # HTTP endpoints
│   ├── service/            # Raft bridge service
│   └── static/             # Web dashboard (HTML/CSS/JS)
├── proto/              # Protocol Buffer definitions
│   └── raft.proto          # RequestVote, AppendEntries RPCs
├── docker/             # Docker deployment
│   ├── Dockerfile          # Multi-stage build (Gradle → JRE Alpine)
│   └── docker-compose.yml  # 3-node cluster config
├── k8s/                # Kubernetes manifests
└── scripts/            # Demo and fault-tolerance scripts
```

## Quick Start

### Prerequisites
- Docker Desktop

### Run the Cluster

```bash
git clone https://github.com/huzvert/raftpay.git
cd raftpay
docker compose -f docker/docker-compose.yml up -d --build
```

First build takes ~5 minutes (Gradle compilation inside Docker). Subsequent starts are fast.

### Open the Dashboard

Go to **http://localhost:8081** in your browser.

### Node Ports

| Node | REST API | gRPC |
|------|----------|------|
| node1 | localhost:8081 | localhost:9091 |
| node2 | localhost:8082 | localhost:9092 |
| node3 | localhost:8083 | localhost:9093 |

## API Endpoints

### Accounts
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/accounts` | Create account |
| GET | `/api/accounts` | List all accounts |
| GET | `/api/accounts/{id}` | Get account details |
| POST | `/api/accounts/{id}/deposit` | Deposit funds |
| POST | `/api/accounts/{id}/withdraw` | Withdraw funds |

### Transfers
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/transfers` | Transfer between accounts |

### Cluster
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/cluster/health` | Node health check |
| GET | `/api/cluster/status` | Node status (term, state, log size) |
| GET | `/api/cluster/all-status` | All nodes status (server-side) |
| GET | `/api/cluster/log` | View Raft log entries |

### Example

```bash
# Create accounts
curl -X POST http://localhost:8081/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"accountId":"alice","ownerName":"Alice","initialBalance":1000}'

# Transfer funds
curl -X POST http://localhost:8081/api/transfers \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId":"alice","toAccountId":"bob","amount":250}'
```

## Fault Tolerance Demo

```bash
# Kill the leader node
bash scripts/kill-node.sh 1

# Cluster elects a new leader automatically
# Make transfers — they still work!

# Bring the node back
bash scripts/restart-node.sh 1

# Node catches up via log replication
```

## How Raft Works (Simplified)

1. **Leader Election** — nodes start as followers. If a follower doesn't hear from a leader within a random timeout (10-20s), it becomes a candidate and requests votes. A candidate that gets majority votes becomes leader.

2. **Log Replication** — the leader receives client requests, appends them to its log, and sends them to followers via `AppendEntries` RPCs. Once a majority confirms, the entry is committed.

3. **Safety** — committed entries are never lost. If a leader fails, the new leader has all committed entries (guaranteed by the voting rules).

4. **Consistency** — all nodes apply the same commands in the same order, so their state machines stay identical.

## Useful Commands

```bash
# Start cluster
docker compose -f docker/docker-compose.yml up -d --build

# Stop cluster (data preserved)
docker compose -f docker/docker-compose.yml down

# Stop cluster and wipe all data
docker compose -f docker/docker-compose.yml down -v

# View logs
docker logs raftpay-node1 -f

# Run full demo
bash scripts/demo.sh
```
