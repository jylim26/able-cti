package com.itsconv.cti.call.domain;

import lombok.Getter;

@Getter
public class CallLeg {

    private final String uniqueId;
    private final String channel;
    private boolean alive = true;

    CallLeg(String uniqueId, String channel) {
        this.uniqueId = uniqueId;
        this.channel = channel;
    }

    void ended() {
        this.alive = false;
    }
}
