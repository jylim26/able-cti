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
    private String ringingChannel;
    private String agent;
    private String agentChannel;
    private Instant answeredAt;
    private boolean held;

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

    public static Call startOutbound(String linkedid, String agentInterface, String agentExtension, String customerNumber, String agentChannel) {
        Call call = new Call(linkedid, CallDirection.OUTBOUND, agentExtension, customerNumber);
        call.agent = agentInterface;
        call.agentChannel = agentChannel;
        return call;
    }

    public synchronized void enqueued(String queueName) {
        requireState(CallState.RINGING);
        this.queueName = queueName;
        this.state = CallState.QUEUED;
    }

    public synchronized void agentRinging(String agentInterface, String channel) {
        this.ringingAgent = agentInterface;
        this.ringingChannel = channel;
    }

    public synchronized boolean agentRingCanceled(String agentInterface) {
        if (agentInterface != null && agentInterface.equals(this.ringingAgent)) {
            this.ringingAgent = null;
            this.ringingChannel = null;
            return true;
        }
        return false;
    }

    public synchronized void connected(String agentInterface, String channel) {
        requireState(CallState.QUEUED);
        this.agent = agentInterface;
        this.agentChannel = channel;
        this.ringingAgent = null;
        this.ringingChannel = null;
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

    // bridge 이벤트로 레그 위치를 갱신하고 보류 여부를 다시 계산한다. 바뀌었으면 true (ADR-0012)
    public synchronized boolean legEnteredBridge(String uniqueId, String bridgeId) {
        CallLeg leg = legs.get(uniqueId);
        if (leg == null) {
            return false;
        }
        leg.enteredBridge(bridgeId);
        return recomputeHeld();
    }

    public synchronized boolean legLeftBridge(String uniqueId, String bridgeId) {
        CallLeg leg = legs.get(uniqueId);
        if (leg == null) {
            return false;
        }
        leg.leftBridge(bridgeId);
        return recomputeHeld();
    }

    private boolean recomputeHeld() {
        boolean newHeld = computeHeld();
        if (newHeld == held) {
            return false;
        }
        this.held = newHeld;
        return true;
    }

    // 보류 = 상담원 레그의 bridge에 살아 있는 hold 레그가 같이 있다.
    // "다른 bridge" 기준은 협의 전환과 topology가 겹쳐 쓰지 않는다 (ADR-0012)
    private boolean computeHeld() {
        if (agentChannel == null) {
            return false;
        }
        String agentBridge = legs.values().stream()
                .filter(leg -> leg.isAlive() && agentChannel.equals(leg.getChannel()))
                .map(CallLeg::getBridgeId)
                .filter(bridgeId -> bridgeId != null)
                .findFirst().orElse(null);
        if (agentBridge == null) {
            return false;
        }
        return legs.values().stream()
                .anyMatch(leg -> leg.isAlive() && leg.isHoldLeg() && agentBridge.equals(leg.getBridgeId()));
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
