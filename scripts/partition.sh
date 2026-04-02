#!/bin/bash
# Simulate a network partition by disconnecting a node from the cluster network
# Usage: ./partition.sh <node_number>

if [ -z "$1" ]; then
    echo "Usage: $0 <node_number (1-5)>"
    exit 1
fi

NODE="raftpay-node$1"
echo "Partitioning $NODE from the cluster network..."
docker network disconnect raft-net "$NODE"
echo "$NODE is now isolated. It cannot communicate with other nodes."
echo "The remaining nodes will continue operating if they have a majority."
