package com.itsconv.cti.call.event;

public record OutboundCallFailedEvent(String callId, String loginId, Integer reason) {
}
