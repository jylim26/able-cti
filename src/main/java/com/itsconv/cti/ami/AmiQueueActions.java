package com.itsconv.cti.ami;

import lombok.RequiredArgsConstructor;
import org.asteriskjava.manager.ManagerConnection;
import org.asteriskjava.manager.action.ManagerAction;
import org.asteriskjava.manager.action.QueueAddAction;
import org.asteriskjava.manager.action.QueuePauseAction;
import org.asteriskjava.manager.action.QueueRemoveAction;
import org.asteriskjava.manager.response.ManagerError;
import org.asteriskjava.manager.response.ManagerResponse;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AmiQueueActions {

    private static final long TIMEOUT_MS = 5000;

    private final ManagerConnection connection;

    public void addMemberPaused(String queue, String memberInterface) {
        QueueAddAction action = new QueueAddAction();
        action.setQueue(queue);
        action.setInterface(memberInterface);
        action.setMemberName(memberInterface);
        action.setPaused(true);
        send(action);
    }

    public void pause(String queue, String memberInterface, String reason) {
        QueuePauseAction action = new QueuePauseAction();
        action.setQueue(queue);
        action.setInterface(memberInterface);
        action.setPaused(true);
        action.setReason(reason);
        send(action);
    }

    public void unpause(String queue, String memberInterface) {
        QueuePauseAction action = new QueuePauseAction();
        action.setQueue(queue);
        action.setInterface(memberInterface);
        action.setPaused(false);
        send(action);
    }

    public void removeMember(String queue, String memberInterface) {
        QueueRemoveAction action = new QueueRemoveAction();
        action.setQueue(queue);
        action.setInterface(memberInterface);
        send(action);
    }

    private void send(ManagerAction action) {
        ManagerResponse response;
        try {
            response = connection.sendAction(action, TIMEOUT_MS);
        } catch (Exception e) {
            throw new AmiActionException("%s failed".formatted(action.getAction()), e);
        }
        if (response instanceof ManagerError) {
            throw new AmiActionException("%s failed: %s".formatted(action.getAction(), response.getMessage()));
        }
    }
}
