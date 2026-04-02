#!/bin/bash
# Heal a network partition by reconnecting a node to the cluster network
# Usage: ./heal-partition.sh <node_number>

if [ -z "$1" ]; then
    echo "Usage: $0 <node_number (1-3)>"
    exit 1
fi

NODE="raftpay-node$1"
echo "Reconnecting $NODE to the cluster network..."
docker network connect raft-net "$NODE"
echo "$NODE reconnected. It will catch up via log replication from the leader."
