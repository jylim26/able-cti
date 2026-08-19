package com.itsconv.cti.push;

import com.itsconv.cti.agent.event.AgentStateChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentStatePushListener {

    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void onAgentStateChanged(AgentStateChangedEvent event) {
        messagingTemplate.convertAndSend("/topic/agents/%s".formatted(event.loginId()), AgentStateMessage.from(event));
    }
}
