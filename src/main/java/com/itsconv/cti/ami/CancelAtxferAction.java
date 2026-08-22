package com.itsconv.cti.ami;

import org.asteriskjava.manager.action.AbstractManagerAction;

// asterisk-java 3.41에 없는 액션이라 직접 정의한다 (ADR-0012)
public class CancelAtxferAction extends AbstractManagerAction {

    private String channel;

    public CancelAtxferAction(String channel) {
        this.channel = channel;
    }

    @Override
    public String getAction() {
        return "CancelAtxfer";
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
