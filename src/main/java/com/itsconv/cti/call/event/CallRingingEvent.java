package com.itsconv.cti.call.event;

public record CallRingingEvent(String callId, String agentInterface, String callerNumber, String queueName) {
}
