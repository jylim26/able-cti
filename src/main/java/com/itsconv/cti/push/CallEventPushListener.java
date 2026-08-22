package com.itsconv.cti.push;

import com.itsconv.cti.agent.AgentSessionRegistry;
import com.itsconv.cti.call.event.CallConnectedEvent;
import com.itsconv.cti.call.event.CallDialingEvent;
import com.itsconv.cti.call.event.CallEndedEvent;
import com.itsconv.cti.call.event.CallHeldEvent;
import com.itsconv.cti.call.event.CallResumedEvent;
import com.itsconv.cti.call.event.CallRingingCanceledEvent;
import com.itsconv.cti.call.event.CallRingingEvent;
import com.itsconv.cti.call.event.OutboundCallFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CallEventPushListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final AgentSessionRegistry sessions;

    @EventListener
    public void onCallRinging(CallRingingEvent event) {
        send(event.agentInterface(), CallEventMessage.ringing(event));
    }

    @EventListener
    public void onCallRingingCanceled(CallRingingCanceledEvent event) {
        send(event.agentInterface(), CallEventMessage.ringingCanceled(event));
    }

    @EventListener
    public void onCallDialing(CallDialingEvent event) {
        send(event.agentInterface(), CallEventMessage.dialing(event));
    }

    @EventListener
    public void onCallConnected(CallConnectedEvent event) {
        send(event.agentInterface(), CallEventMessage.answered(event));
    }

    @EventListener
    public void onCallHeld(CallHeldEvent event) {
        send(event.agentInterface(), CallEventMessage.held(event));
    }

    @EventListener
    public void onCallResumed(CallResumedEvent event) {
        send(event.agentInterface(), CallEventMessage.resumed(event));
    }

    @EventListener
    public void onCallEnded(CallEndedEvent event) {
        if (event.agentInterface() == null) {
            return;
        }
        send(event.agentInterface(), CallEventMessage.ended(event));
    }

    @EventListener
    public void onOutboundCallFailed(OutboundCallFailedEvent event) {
        messagingTemplate.convertAndSend("/topic/agents/%s".formatted(event.loginId()), CallEventMessage.outboundFailed(event));
    }

    private void send(String agentInterface, CallEventMessage message) {
        sessions.findByInterface(agentInterface).ifPresentOrElse(
                session -> messagingTemplate.convertAndSend("/topic/agents/%s".formatted(session.getLoginId()), message),
                () -> log.warn("no agent session for interface {}, dropped {} of call {}", agentInterface, message.event(), message.data().callId()));
    }
}
