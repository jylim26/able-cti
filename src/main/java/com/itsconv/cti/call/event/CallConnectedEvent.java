package com.itsconv.cti.call.event;

public record CallConnectedEvent(String callId, String agentInterface, String direction) {
}
