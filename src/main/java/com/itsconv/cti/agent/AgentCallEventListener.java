package com.itsconv.cti.agent;

import com.itsconv.cti.call.event.CallConnectedEvent;
import com.itsconv.cti.call.event.CallEndedEvent;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCallEventListener {

    private final AgentService agentService;
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();

    @EventListener
    public void onCallConnected(CallConnectedEvent event) {
        agentService.callConnected(event.agentInterface(), event.callId(), event.direction());
    }

    @EventListener
    public void onCallEnded(CallEndedEvent event) {
        if (!event.answered()) {
            return;
        }
        commandExecutor.submit(() -> {
            try {
                if ("INBOUND".equals(event.direction())) {
                    agentService.queueInboundCallEnded(event.agentInterface());
                } else {
                    agentService.outboundCallEnded(event.agentInterface());
                }
            } catch (Exception e) {
                log.error("failed to handle call end: interface={}", event.agentInterface(), e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        commandExecutor.shutdown();
    }
}
