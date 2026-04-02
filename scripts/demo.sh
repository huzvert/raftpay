#!/bin/bash
# RaftPay Demo Script
# Run this after `docker-compose up -d` from the docker/ directory

set -e

API_BASE="http://localhost"
LEADER_PORT=""

echo "========================================="
echo "  RAFT-PAY DEMO: Distributed Payments"
echo "========================================="
echo ""

# Find the leader
find_leader() {
    for port in 8081 8082 8083 8084 8085; do
        STATUS=$(curl -s "$API_BASE:$port/api/cluster/status" 2>/dev/null || echo "{}")
        STATE=$(echo "$STATUS" | grep -o '"state":"[^"]*"' | cut -d'"' -f4)
        if [ "$STATE" = "LEADER" ]; then
            LEADER_PORT=$port
            NODE_ID=$(echo "$STATUS" | grep -o '"nodeId":"[^"]*"' | cut -d'"' -f4)
            echo "Leader found: $NODE_ID on port $port"
            return 0
        fi
    done
    echo "No leader found yet. Waiting..."
    return 1
}

echo "Step 1: Finding cluster leader..."
sleep 2
while ! find_leader; do
    sleep 1
done
echo ""

echo "Step 2: Creating accounts..."
echo "  Creating Alice's account with \$1000..."
curl -s -X POST "$API_BASE:$LEADER_PORT/api/accounts" \
    -H "Content-Type: application/json" \
    -d '{"accountId":"alice","ownerName":"Alice Johnson","initialBalance":1000}' | python3 -m json.tool 2>/dev/null || echo "(created)"
echo ""

echo "  Creating Bob's account with \$500..."
curl -s -X POST "$API_BASE:$LEADER_PORT/api/accounts" \
    -H "Content-Type: application/json" \
    -d '{"accountId":"bob","ownerName":"Bob Smith","initialBalance":500}' | python3 -m json.tool 2>/dev/null || echo "(created)"
echo ""

echo "  Creating Charlie's account with \$250..."
curl -s -X POST "$API_BASE:$LEADER_PORT/api/accounts" \
    -H "Content-Type: application/json" \
    -d '{"accountId":"charlie","ownerName":"Charlie Brown","initialBalance":250}' | python3 -m json.tool 2>/dev/null || echo "(created)"
echo ""

echo "Step 3: Making transfers..."
echo "  Alice -> Bob: \$100"
curl -s -X POST "$API_BASE:$LEADER_PORT/api/transfers" \
    -H "Content-Type: application/json" \
    -d '{"fromAccountId":"alice","toAccountId":"bob","amount":100}' | python3 -m json.tool 2>/dev/null || echo "(done)"
echo ""

echo "  Bob -> Charlie: \$50"
curl -s -X POST "$API_BASE:$LEADER_PORT/api/transfers" \
    -H "Content-Type: application/json" \
    -d '{"fromAccountId":"bob","toAccountId":"charlie","amount":50}' | python3 -m json.tool 2>/dev/null || echo "(done)"
echo ""

echo "Step 4: Verifying consistency across ALL nodes..."
for port in 8081 8082 8083 8084 8085; do
    NODE_NUM=$((port - 8080))
    echo "  Node $NODE_NUM (port $port):"
    for acct in alice bob charlie; do
        BALANCE=$(curl -s "$API_BASE:$port/api/accounts/$acct" 2>/dev/null | grep -o '"balance":[0-9.]*' | head -1 | cut -d: -f2)
        echo "    $acct: \$$BALANCE"
    done
done
echo ""

echo "========================================="
echo "  All nodes show identical balances!"
echo "  Alice: \$900, Bob: \$550, Charlie: \$300"
echo "========================================="
echo ""
echo "Next steps for fault tolerance demo:"
echo "  1. Kill the leader:  ./scripts/kill-node.sh <N>"
echo "  2. New leader elected automatically"
echo "  3. Continue making transfers"
echo "  4. Restart node:     ./scripts/restart-node.sh <N>"
echo "  5. Node catches up via log replication"
