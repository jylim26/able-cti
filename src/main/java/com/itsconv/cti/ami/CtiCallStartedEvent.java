package com.itsconv.cti.ami;

import lombok.Getter;
import lombok.Setter;
import org.asteriskjava.manager.event.UserEvent;

@Getter
@Setter
public class CtiCallStartedEvent extends UserEvent {

    private String linkedid;
    private String direction;

    public CtiCallStartedEvent(Object source) {
        super(source);
    }
}
