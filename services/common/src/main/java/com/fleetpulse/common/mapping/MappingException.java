package com.fleetpulse.common.mapping;

/**
 * Raised when a payload cannot be turned into a valid canonical event.
 * The {@code reason} is a stable code written to the DLQ record header, so failures
 * can be counted, alerted on and replayed by category.
 */
public class MappingException extends RuntimeException {

    private final String reason;

    public MappingException(String reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
