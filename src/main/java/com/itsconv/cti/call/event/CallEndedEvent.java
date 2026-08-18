package com.itsconv.cti.call.event;

public record CallEndedEvent(String callId, String agentInterface, boolean answered, String direction) {
}
