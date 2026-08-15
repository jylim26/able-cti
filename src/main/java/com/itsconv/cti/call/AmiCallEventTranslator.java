package com.itsconv.cti.call;

import com.itsconv.cti.ami.CtiCallStartedEvent;
import com.itsconv.cti.call.domain.Call;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.asteriskjava.manager.ManagerEventListener;
import org.asteriskjava.manager.event.AgentConnectEvent;
import org.asteriskjava.manager.event.HangupEvent;
import org.asteriskjava.manager.event.ManagerEvent;
import org.asteriskjava.manager.event.NewChannelEvent;
import org.asteriskjava.manager.event.QueueCallerJoinEvent;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AmiCallEventTranslator implements ManagerEventListener {

    private static final String INBOUND = "INBOUND";

    private final CallRegistry registry;

    @Override
    public void onManagerEvent(ManagerEvent event) {
        try {
            switch (event) {
                case CtiCallStartedEvent e -> onCtiCallStarted(e);
                case NewChannelEvent e -> onNewChannel(e);
                case QueueCallerJoinEvent e -> onQueueCallerJoin(e);
                case AgentConnectEvent e -> onAgentConnect(e);
                case HangupEvent e -> onHangup(e);
                default -> { }
            }
        } catch (Exception e) {
            log.error("failed to handle AMI event {}", event.getClass().getSimpleName(), e);
        }
    }

    private void onCtiCallStarted(CtiCallStartedEvent e) {
        String linkedid = e.getLinkedid();
        if (linkedid == null || !linkedid.equals(e.getUniqueId()) || registry.find(linkedid).isPresent()) {
            return;
        }
        if (!INBOUND.equals(e.getDirection())) {
            log.warn("ignored CtiCallStarted with direction {} for {}", e.getDirection(), linkedid);
            return;
        }
        Call call = Call.start(linkedid, e.getCallerIdNum(), e.getExten());
        call.legStarted(e.getUniqueId(), e.getChannel());
        registry.put(call);
        log.info("call started: linkedid={} caller={} called={}", linkedid, call.getCallerNumber(), call.getCalledNumber());
    }

    private void onNewChannel(NewChannelEvent e) {
        registry.find(e.getLinkedid()).ifPresent(call -> {
            call.legStarted(e.getUniqueId(), e.getChannel());
            log.info("leg started: linkedid={} channel={}", call.getLinkedid(), e.getChannel());
        });
    }

    private void onQueueCallerJoin(QueueCallerJoinEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> log.info("queue joined: linkedid={} queue={} position={}", call.getLinkedid(), e.getQueue(), e.getPosition()));
    }

    private void onAgentConnect(AgentConnectEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> log.info("agent connected: linkedid={} interface={}", call.getLinkedid(), e.getInterface()));
    }

    private void onHangup(HangupEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> {
            boolean lastLegEnded = call.legEnded(e.getUniqueId());
            log.info("leg ended: linkedid={} channel={}", call.getLinkedid(), e.getChannel());
            if (lastLegEnded) {
                registry.remove(call.getLinkedid());
                log.info("call ended: linkedid={}", call.getLinkedid());
            }
        });
    }
}
