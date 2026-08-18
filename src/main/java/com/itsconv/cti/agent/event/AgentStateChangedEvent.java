package com.itsconv.cti.agent.event;

public record AgentStateChangedEvent(
        String loginId,
        String name,
        String extension,
        String status,
        String pauseReason,
        String callId,
        String callDirection) {
}
