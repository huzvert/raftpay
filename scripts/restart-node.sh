#!/bin/bash
# Restart a specific RaftPay node
# Usage: ./restart-node.sh <node_number>

if [ -z "$1" ]; then
    echo "Usage: $0 <node_number (1-5)>"
    exit 1
fi

NODE="raftpay-node$1"
echo "Starting $NODE..."
docker start "$NODE"
echo "$NODE started. It will rejoin the cluster and catch up via log replication."
