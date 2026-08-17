package com.itsconv.cti.agent.domain;

public final class PauseReason {

    public static final String LOGIN = "LOGIN";
    public static final String ACW = "ACW";

    private PauseReason() {
    }

    public static boolean isReserved(String reason) {
        return LOGIN.equals(reason) || ACW.equals(reason);
    }
}
