package com.itsconv.cti.push;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.itsconv.cti.agent.event.AgentStateChangedEvent;

public record AgentStateMessage(String type, Data data) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Data(String loginId, String name, String extension, String status, String pauseReason, String callId, String callDirection) {
    }

    public static AgentStateMessage from(AgentStateChangedEvent event) {
        return new AgentStateMessage("AGENT", new Data(event.loginId(), event.name(), event.extension(), event.status(), event.pauseReason(), event.callId(), event.callDirection()));
    }
}
