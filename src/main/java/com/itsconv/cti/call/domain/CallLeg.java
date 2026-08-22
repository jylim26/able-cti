package com.itsconv.cti.call.domain;

import lombok.Getter;

@Getter
public class CallLeg {

    private final String uniqueId;
    private final String channel;
    private boolean alive = true;
    private String bridgeId;

    CallLeg(String uniqueId, String channel) {
        this.uniqueId = uniqueId;
        this.channel = channel;
    }

    void ended() {
        this.alive = false;
    }

    void enteredBridge(String bridgeId) {
        this.bridgeId = bridgeId;
    }

    void leftBridge(String bridgeId) {
        if (bridgeId != null && bridgeId.equals(this.bridgeId)) {
            this.bridgeId = null;
        }
    }

    // [hold] context가 만드는 Local 채널만 이 이름을 가진다 (ADR-0012)
    boolean isHoldLeg() {
        return channel != null && channel.contains("@hold-");
    }
}
