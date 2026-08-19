package com.itsconv.cti.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itsconv.cti.agent.AgentSessionRegistry;
import com.itsconv.cti.agent.domain.AgentSession;
import com.itsconv.cti.call.event.CallEndedEvent;
import com.itsconv.cti.call.event.CallRingingCanceledEvent;
import com.itsconv.cti.call.event.CallRingingEvent;
import com.itsconv.cti.call.event.OutboundCallFailedEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class CallEventPushListenerTest {

    private static final String CALL_ID = "1755000000.100";
    private static final String INTERFACE = "PJSIP/1000";

    private RecordingMessagingTemplate messagingTemplate;
    private AgentSessionRegistry registry;
    private CallEventPushListener listener;

    @BeforeEach
    void setUp() {
        messagingTemplate = new RecordingMessagingTemplate();
        registry = new AgentSessionRegistry();
        listener = new CallEventPushListener(messagingTemplate, registry);
    }

    @Test
    void 착신은_그_상담원의_토픽으로_푸시됨() {
        registry.put(AgentSession.login(1L, "agent1", "상담원1", "1000", INTERFACE, List.of("queue01")));

        listener.onCallRinging(new CallRingingEvent(CALL_ID, INTERFACE, "01012345678", "queue01"));

        assertEquals(1, messagingTemplate.sent.size());
        assertEquals("/topic/agents/agent1", messagingTemplate.sent.get(0).destination());
        assertEquals(new CallEventMessage("CALL", "RINGING", new CallEventMessage.Data(CALL_ID, "01012345678", "queue01", null, null)), messagingTemplate.sent.get(0).payload());
    }

    @Test
    void 미로그인_상담원의_콜_이벤트는_드롭됨() {
        listener.onCallRingingCanceled(new CallRingingCanceledEvent(CALL_ID, INTERFACE));

        assertTrue(messagingTemplate.sent.isEmpty());
    }

    @Test
    void 발신_실패는_세션_조회_없이_loginId_토픽으로_푸시됨() {
        listener.onOutboundCallFailed(new OutboundCallFailedEvent(CALL_ID, "agent1", 3));

        assertEquals(1, messagingTemplate.sent.size());
        assertEquals("/topic/agents/agent1", messagingTemplate.sent.get(0).destination());
        assertEquals(new CallEventMessage("CALL", "OUTBOUND_FAILED", new CallEventMessage.Data(CALL_ID, null, null, null, 3)), messagingTemplate.sent.get(0).payload());
    }

    @Test
    void 상담원이_확정되지_않은_종료는_푸시하지_않음() {
        registry.put(AgentSession.login(1L, "agent1", "상담원1", "1000", INTERFACE, List.of("queue01")));

        listener.onCallEnded(new CallEndedEvent(CALL_ID, null, false, "INBOUND"));

        assertTrue(messagingTemplate.sent.isEmpty());
    }

    private static class RecordingMessagingTemplate extends SimpMessagingTemplate {

        record Sent(String destination, Object payload) {
        }

        final List<Sent> sent = new ArrayList<>();

        RecordingMessagingTemplate() {
            super((message, timeout) -> true);
        }

        @Override
        public void convertAndSend(String destination, Object payload) {
            sent.add(new Sent(destination, payload));
        }
    }
}
