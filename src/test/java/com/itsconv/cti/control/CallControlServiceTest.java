package com.itsconv.cti.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itsconv.cti.agent.AgentSessionRegistry;
import com.itsconv.cti.agent.domain.AgentSession;
import com.itsconv.cti.agent.domain.PauseReason;
import com.itsconv.cti.ami.AmiActionException;
import com.itsconv.cti.ami.AmiOriginateActions;
import com.itsconv.cti.call.PendingOutboundRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallControlServiceTest {

    private static final String INTERFACE = "PJSIP/1000";

    private AgentSessionRegistry sessions;
    private PendingOutboundRegistry pendingOutbound;
    private RecordingOriginateActions originateActions;
    private CallControlService service;

    @BeforeEach
    void setUp() {
        sessions = new AgentSessionRegistry();
        pendingOutbound = new PendingOutboundRegistry();
        originateActions = new RecordingOriginateActions();
        service = new CallControlService(sessions, pendingOutbound, originateActions, new OutboundProperties("from-internal", 15000));
    }

    @Test
    void OUTBOUND_이석_상태에서_발신하면_pending_등록과_Originate_전송() {
        loggedIn().pause(PauseReason.OUTBOUND);

        String callId = service.clickToCall("agent1", "01012345678");

        assertTrue(callId.startsWith("cti-"));
        assertEquals(List.of("originate %s %s from-internal 01012345678 15000".formatted(INTERFACE, callId)), originateActions.sent);
        PendingOutboundRegistry.PendingOutbound pending = pendingOutbound.consume(callId).orElseThrow();
        assertEquals("agent1", pending.loginId());
        assertEquals(INTERFACE, pending.agentInterface());
        assertEquals("01012345678", pending.customerNumber());
    }

    @Test
    void 로그인하지_않은_상담원의_발신은_거부됨() {
        assertThrows(NoSuchElementException.class, () -> service.clickToCall("agent1", "01012345678"));
    }

    @Test
    void OUTBOUND_이석이_아니면_발신이_거부됨() {
        AgentSession session = loggedIn();
        assertThrows(IllegalStateException.class, () -> service.clickToCall("agent1", "01012345678"));

        session.pause("lunch");
        assertThrows(IllegalStateException.class, () -> service.clickToCall("agent1", "01012345678"));

        assertTrue(originateActions.sent.isEmpty());
    }

    @Test
    void 번호_없는_발신은_거부됨() {
        loggedIn().pause(PauseReason.OUTBOUND);

        assertThrows(IllegalArgumentException.class, () -> service.clickToCall("agent1", " "));
    }

    @Test
    void Originate_전송_실패면_pending을_정리하고_예외를_던짐() {
        loggedIn().pause(PauseReason.OUTBOUND);
        originateActions.failNext = true;

        assertThrows(AmiActionException.class, () -> service.clickToCall("agent1", "01012345678"));

        assertTrue(originateActions.registeredAtSend.stream().allMatch(callId -> pendingOutbound.consume(callId).isEmpty()));
    }

    private AgentSession loggedIn() {
        AgentSession session = AgentSession.login(1L, "agent1", "상담원1", "1000", INTERFACE, List.of("queue01"));
        session.unpause();
        sessions.put(session);
        return session;
    }

    private class RecordingOriginateActions extends AmiOriginateActions {

        private final List<String> sent = new ArrayList<>();
        private final List<String> registeredAtSend = new ArrayList<>();
        private boolean failNext;

        private RecordingOriginateActions() {
            super(null);
        }

        @Override
        public void originate(String channel, String channelId, String context, String exten, long ringTimeoutMs) {
            registeredAtSend.add(channelId);
            if (failNext) {
                throw new AmiActionException("Originate failed");
            }
            sent.add("originate %s %s %s %s %d".formatted(channel, channelId, context, exten, ringTimeoutMs));
        }
    }
}
