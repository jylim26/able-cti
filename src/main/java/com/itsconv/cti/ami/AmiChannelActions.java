package com.itsconv.cti.ami;

import lombok.RequiredArgsConstructor;
import org.asteriskjava.manager.ManagerConnection;
import org.asteriskjava.manager.action.HangupAction;
import org.asteriskjava.manager.action.ManagerAction;
import org.asteriskjava.manager.action.PJSIPNotifyAction;
import org.asteriskjava.manager.response.ManagerError;
import org.asteriskjava.manager.response.ManagerResponse;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AmiChannelActions {

    private static final long ACTION_TIMEOUT_MS = 5000;

    private final ManagerConnection connection;

    // 채널을 지정해야 울리는 INVITE 다이얼로그 안(in-dialog)으로 NOTIFY가 나간다 (ADR-0011)
    public void notifyTalk(String channel) {
        PJSIPNotifyAction action = new PJSIPNotifyAction();
        action.setChannel(channel);
        action.setVariable("Event", "talk");
        send(action, "PJSIPNotify");
    }

    public void hangup(String channel) {
        HangupAction action = new HangupAction(channel);
        send(action, "Hangup");
    }

    private void send(ManagerAction action, String name) {
        ManagerResponse response;
        try {
            response = connection.sendAction(action, ACTION_TIMEOUT_MS);
        } catch (Exception e) {
            throw new AmiActionException("%s failed".formatted(name), e);
        }
        if (response instanceof ManagerError) {
            throw new AmiActionException("%s failed: %s".formatted(name, response.getMessage()));
        }
    }
}
