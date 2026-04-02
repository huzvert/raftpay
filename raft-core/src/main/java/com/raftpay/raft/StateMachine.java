package com.raftpay.raft;

/**
 * Interface for the application-level state machine.
 * Raft replicates commands; the state machine interprets and applies them.
 */
public interface StateMachine {

    /**
     * Apply a committed command to the state machine.
     *
     * @param command the serialized command bytes
     * @return serialized result bytes
     */
    byte[] apply(byte[] command);
}
