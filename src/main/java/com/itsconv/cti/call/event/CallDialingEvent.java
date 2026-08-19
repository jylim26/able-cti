package com.itsconv.cti.call.event;

public record CallDialingEvent(String callId, String agentInterface, String customerNumber) {
}
