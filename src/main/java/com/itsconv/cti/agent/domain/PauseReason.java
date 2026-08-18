package com.itsconv.cti.agent.domain;

public final class PauseReason {

    public static final String LOGIN = "LOGIN";
    public static final String ACW = "ACW";
    public static final String OUTBOUND = "OUTBOUND";

    private PauseReason() {
    }

    public static boolean isSystemOnly(String reason) {
        return LOGIN.equals(reason) || ACW.equals(reason);
    }
}
