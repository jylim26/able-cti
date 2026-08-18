package com.itsconv.cti.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itsconv.cti.agent.domain.AgentSession;
import com.itsconv.cti.agent.domain.AgentStatus;
import com.itsconv.cti.agent.domain.PauseReason;
import com.itsconv.cti.ami.AmiQueueActions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentServiceTest {

    private static final String INTERFACE = "PJSIP/1000";
    private static final String CALL_ID = "1755000000.100";

    private AgentSessionRegistry registry;
    private RecordingQueueActions queueActions;
    private AgentService service;

    @BeforeEach
    void setUp() {
        registry = new AgentSessionRegistry();
        queueActions = new RecordingQueueActions();
        service = new AgentService(null, registry, queueActions, event -> {
        });
    }

    @Test
    void 콜_연결로_READY_세션이_ON_CALL이_됨() {
        AgentSession session = loggedInReady();

        service.callConnected(INTERFACE, CALL_ID, "INBOUND");

        assertEquals(AgentStatus.ON_CALL, session.getStatus());
        assertEquals(CALL_ID, session.getCallId());
        assertEquals("INBOUND", session.getCallDirection());
        assertTrue(queueActions.sent.isEmpty());
    }

    @Test
    void 큐_인바운드_종료로_ACW_진입과_큐_이석_명령_전송() {
        AgentSession session = loggedInReady();
        service.callConnected(INTERFACE, CALL_ID, "INBOUND");

        service.queueInboundCallEnded(INTERFACE);

        assertEquals(AgentStatus.PAUSED, session.getStatus());
        assertEquals(PauseReason.ACW, session.getPauseReason());
        assertEquals(CALL_ID, session.getCallId());
        assertEquals(List.of("pause queue01 %s ACW".formatted(INTERFACE)), queueActions.sent);
    }

    @Test
    void 세션_없는_인터페이스의_콜_이벤트는_무시됨() {
        service.callConnected("PJSIP/9999", CALL_ID, "INBOUND");
        service.queueInboundCallEnded("PJSIP/9999");

        assertTrue(queueActions.sent.isEmpty());
    }

    @Test
    void ACW_해제는_기존_unpause로_READY_복귀와_콜_참조_소거() {
        AgentSession session = loggedInReady();
        service.callConnected(INTERFACE, CALL_ID, "INBOUND");
        service.queueInboundCallEnded(INTERFACE);

        service.unpause("agent1");

        assertEquals(AgentStatus.READY, session.getStatus());
        assertNull(session.getCallId());
    }

    @Test
    void 상담원이_OUTBOUND_사유로_이석할_수_있음() {
        AgentSession session = loggedInReady();

        service.pause("agent1", PauseReason.OUTBOUND);

        assertEquals(AgentStatus.PAUSED, session.getStatus());
        assertEquals(PauseReason.OUTBOUND, session.getPauseReason());
    }

    @Test
    void 아웃바운드_종료는_통화_전_이석으로_복귀하고_큐_명령이_없음() {
        AgentSession session = loggedInReady();
        service.pause("agent1", PauseReason.OUTBOUND);
        queueActions.sent.clear();
        service.callConnected(INTERFACE, CALL_ID, "OUTBOUND");

        service.outboundCallEnded(INTERFACE);

        assertEquals(AgentStatus.PAUSED, session.getStatus());
        assertEquals(PauseReason.OUTBOUND, session.getPauseReason());
        assertNull(session.getCallId());
        assertTrue(queueActions.sent.isEmpty());
    }

    private AgentSession loggedInReady() {
        AgentSession session = AgentSession.login(1L, "agent1", "상담원1", "1000", INTERFACE, List.of("queue01"));
        session.unpause();
        registry.put(session);
        return session;
    }

    private static class RecordingQueueActions extends AmiQueueActions {

        private final List<String> sent = new ArrayList<>();

        private RecordingQueueActions() {
            super(null);
        }

        @Override
        public void pause(String queue, String memberInterface, String reason) {
            sent.add("pause %s %s %s".formatted(queue, memberInterface, reason));
        }

        @Override
        public void unpause(String queue, String memberInterface) {
            sent.add("unpause %s %s".formatted(queue, memberInterface));
        }
    }
}
