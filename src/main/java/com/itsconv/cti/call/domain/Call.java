package com.itsconv.cti.call.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;

@Getter
public class Call {

    private final String linkedid;
    private final CallDirection direction;
    private final String callerNumber;
    private final String calledNumber;

    @Getter(AccessLevel.NONE)
    private final Map<String, CallLeg> legs = new LinkedHashMap<>();

    private CallState state;
    private String queueName;
    private String ringingAgent;
    private String agent;
    private Instant answeredAt;

    private Call(String linkedid, CallDirection direction, String callerNumber, String calledNumber) {
        this.linkedid = linkedid;
        this.direction = direction;
        this.callerNumber = callerNumber;
        this.calledNumber = calledNumber;
        this.state = CallState.RINGING;
    }

    public static Call start(String linkedid, String callerNumber, String calledNumber) {
        return new Call(linkedid, CallDirection.INBOUND, callerNumber, calledNumber);
    }

    public static Call startOutbound(String linkedid, String agentInterface, String agentExtension, String customerNumber) {
        Call call = new Call(linkedid, CallDirection.OUTBOUND, agentExtension, customerNumber);
        call.agent = agentInterface;
        return call;
    }

    public synchronized void enqueued(String queueName) {
        requireState(CallState.RINGING);
        this.queueName = queueName;
        this.state = CallState.QUEUED;
    }

    public synchronized void agentRinging(String agentInterface) {
        this.ringingAgent = agentInterface;
    }

    public synchronized boolean agentRingCanceled(String agentInterface) {
        if (agentInterface != null && agentInterface.equals(this.ringingAgent)) {
            this.ringingAgent = null;
            return true;
        }
        return false;
    }

    public synchronized void connected(String agentInterface) {
        requireState(CallState.QUEUED);
        this.agent = agentInterface;
        this.ringingAgent = null;
        this.answeredAt = Instant.now();
        this.state = CallState.CONNECTED;
    }

    public synchronized void customerAnswered() {
        requireState(CallState.RINGING);
        this.answeredAt = Instant.now();
        this.state = CallState.CONNECTED;
    }

    public synchronized boolean isAnswered() {
        return answeredAt != null;
    }

    public synchronized void legStarted(String uniqueId, String channel) {
        legs.putIfAbsent(uniqueId, new CallLeg(uniqueId, channel));
    }

    public synchronized boolean legEnded(String uniqueId) {
        CallLeg leg = legs.get(uniqueId);
        if (leg == null) {
            return false;
        }
        leg.ended();
        boolean lastLegEnded = legs.values().stream().noneMatch(CallLeg::isAlive);
        if (lastLegEnded) {
            this.state = CallState.ENDED;
        }
        return lastLegEnded;
    }

    public synchronized Map<String, CallLeg> legs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(legs));
    }

    private void requireState(CallState required) {
        if (state != required) {
            throw new IllegalStateException("call %s: invalid transition from %s".formatted(linkedid, state));
        }
    }
}
