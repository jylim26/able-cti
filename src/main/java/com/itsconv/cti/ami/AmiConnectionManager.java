package com.itsconv.cti.ami;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.asteriskjava.manager.ManagerConnection;
import org.asteriskjava.manager.ManagerConnectionState;
import org.asteriskjava.manager.ManagerEventListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AmiConnectionManager implements SmartLifecycle {

    private final ManagerConnection connection;

    public AmiConnectionManager(ManagerConnection connection, List<ManagerEventListener> listeners) {
        this.connection = connection;

        connection.addEventListener(event -> log.debug("AMI event: {}", event));
        listeners.forEach(connection::addEventListener);
    }

    @Override
    public void start() {
        try {
            connection.login();
            log.info("AMI login OK: {}:{} (state={})", connection.getHostname(), connection.getPort(), connection.getState());
        } catch (Exception e) {
            throw new IllegalStateException("AMI login failed", e);
        }
    }

    @Override
    public void stop() {
        if (connection.getState() == ManagerConnectionState.CONNECTED) {
            connection.logoff();
        }
    }

    @Override
    public boolean isRunning() {
        return connection.getState() == ManagerConnectionState.CONNECTED;
    }
}
