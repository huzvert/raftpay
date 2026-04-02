#!/bin/bash
# Kill a specific RaftPay node
# Usage: ./kill-node.sh <node_number>

if [ -z "$1" ]; then
    echo "Usage: $0 <node_number (1-3)>"
    exit 1
fi

NODE="raftpay-node$1"
echo "Stopping $NODE..."
docker stop "$NODE"
echo "$NODE stopped. Cluster should elect a new leader if this was the leader."
echo ""
echo "Check cluster status:"
for i in 1 2 3; do
    if [ "$i" != "$1" ]; then
        echo "  curl -s http://localhost:808$i/api/cluster/health"
    fi
done
