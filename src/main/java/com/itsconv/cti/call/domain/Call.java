package com.itsconv.cti.call.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;

@Getter
public class Call {

    private final String linkedid;
    private final String callerNumber;
    private final String calledNumber;

    @Getter(AccessLevel.NONE)
    private final Map<String, CallLeg> legs = new LinkedHashMap<>();

    private Call(String linkedid, String callerNumber, String calledNumber) {
        this.linkedid = linkedid;
        this.callerNumber = callerNumber;
        this.calledNumber = calledNumber;
    }

    public static Call start(String linkedid, String callerNumber, String calledNumber) {
        return new Call(linkedid, callerNumber, calledNumber);
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
        return legs.values().stream().noneMatch(CallLeg::isAlive);
    }

    public synchronized Map<String, CallLeg> legs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(legs));
    }
}
