package com.itsconv.cti.agent.domain;

import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;

@Getter
public class AgentSession {

    private final long agentId;
    private final String name;
    private final String extension;
    private final String queueInterface;

    @Getter(AccessLevel.NONE)
    private final List<String> queues;

    private AgentStatus status;
    private String pauseReason;

    @Getter(AccessLevel.NONE)
    private String pauseAfterCallReason;
    private String callId;
    private String callDirection;

    private AgentSession(long agentId, String name, String extension, String queueInterface, List<String> queues) {
        this.agentId = agentId;
        this.name = name;
        this.extension = extension;
        this.queueInterface = queueInterface;
        this.queues = List.copyOf(queues);
        this.status = AgentStatus.PAUSED;
        this.pauseReason = PauseReason.LOGIN;
    }

    public static AgentSession login(long agentId, String name, String extension, String queueInterface, List<String> queues) {
        return new AgentSession(agentId, name, extension, queueInterface, queues);
    }

    public synchronized void pause(String reason) {
        requireAnyOf(AgentStatus.READY, AgentStatus.PAUSED, AgentStatus.ON_CALL);
        if (status == AgentStatus.ON_CALL) {
            this.pauseAfterCallReason = reason;
            return;
        }
        enterPaused(reason);
    }

    public synchronized void unpause() {
        requireAnyOf(AgentStatus.PAUSED, AgentStatus.ON_CALL);
        if (status == AgentStatus.ON_CALL) {
            this.pauseAfterCallReason = null;
            return;
        }
        enterReady();
    }

    public synchronized void callConnected(String callId, String callDirection) {
        requireAnyOf(AgentStatus.READY, AgentStatus.PAUSED);
        this.pauseAfterCallReason = status == AgentStatus.PAUSED ? pauseReason : null;
        this.pauseReason = null;
        this.callId = callId;
        this.callDirection = callDirection;
        this.status = AgentStatus.ON_CALL;
    }

    public synchronized void normalCallEnded() {
        requireAnyOf(AgentStatus.ON_CALL);
        if (pauseAfterCallReason != null) {
            enterPaused(pauseAfterCallReason);
        } else {
            enterReady();
        }
    }

    public synchronized void queueInboundCallEnded() {
        requireAnyOf(AgentStatus.ON_CALL);
        enterAfterCallWork();
    }

    public synchronized void logout() {
        enterLoggedOut();
    }

    public List<String> queues() {
        return queues;
    }

    private void enterReady() {
        this.status = AgentStatus.READY;
        this.pauseReason = null;
        this.pauseAfterCallReason = null;
        clearCallRef();
    }

    private void enterPaused(String reason) {
        this.status = AgentStatus.PAUSED;
        this.pauseReason = reason;
        this.pauseAfterCallReason = null;
        clearCallRef();
    }

    private void enterAfterCallWork() {
        this.status = AgentStatus.PAUSED;
        this.pauseReason = PauseReason.ACW;
        this.pauseAfterCallReason = null;
    }

    private void enterLoggedOut() {
        this.status = AgentStatus.LOGGED_OUT;
        this.pauseReason = null;
        this.pauseAfterCallReason = null;
        clearCallRef();
    }

    private void clearCallRef() {
        this.callId = null;
        this.callDirection = null;
    }

    private void requireAnyOf(AgentStatus... allowed) {
        for (AgentStatus s : allowed) {
            if (status == s) {
                return;
            }
        }
        throw new IllegalStateException("agent %s: invalid transition from %s".formatted(extension, status));
    }
}
