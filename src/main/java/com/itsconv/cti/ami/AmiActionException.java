package com.itsconv.cti.ami;

public class AmiActionException extends RuntimeException {

    public AmiActionException(String message) {
        super(message);
    }

    public AmiActionException(String message, Throwable cause) {
        super(message, cause);
    }
}
