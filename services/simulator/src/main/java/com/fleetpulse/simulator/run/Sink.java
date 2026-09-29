package com.fleetpulse.simulator.run;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Where simulated OEM payloads are delivered. Implementations must never drop a batch. */
public interface Sink extends AutoCloseable {

    /** Delivers a batch for one OEM. May block when the downstream applies back-pressure. */
    void send(String oem, List<ObjectNode> batch) throws InterruptedException;

    @Override
    default void close() {}
}
