package com.itsconv.cti.ami;

import lombok.RequiredArgsConstructor;
import org.asteriskjava.manager.ManagerConnection;
import org.asteriskjava.manager.action.OriginateAction;
import org.asteriskjava.manager.response.ManagerError;
import org.asteriskjava.manager.response.ManagerResponse;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AmiOriginateActions {

    private static final long ACTION_TIMEOUT_MS = 5000;

    private final ManagerConnection connection;

    public void originate(String channel, String channelId, String context, String exten, long ringTimeoutMs) {
        OriginateAction action = new OriginateAction();
        action.setChannel(channel);
        action.setChannelId(channelId);
        action.setContext(context);
        action.setExten(exten);
        action.setPriority(1);
        action.setCallerId(exten);
        action.setTimeout(ringTimeoutMs);
        action.setAsync(Boolean.TRUE);
        send(action);
    }

    private void send(OriginateAction action) {
        ManagerResponse response;
        try {
            response = connection.sendAction(action, ACTION_TIMEOUT_MS);
        } catch (Exception e) {
            throw new AmiActionException("Originate failed", e);
        }
        if (response instanceof ManagerError) {
            throw new AmiActionException("Originate failed: %s".formatted(response.getMessage()));
        }
    }
}
