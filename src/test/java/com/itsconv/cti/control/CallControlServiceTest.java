package com.itsconv.cti.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itsconv.cti.agent.AgentSessionRegistry;
import com.itsconv.cti.agent.domain.AgentSession;
import com.itsconv.cti.agent.domain.PauseReason;
import com.itsconv.cti.ami.AmiActionException;
import com.itsconv.cti.ami.AmiChannelActions;
import com.itsconv.cti.ami.AmiOriginateActions;
import com.itsconv.cti.call.CallRegistry;
import com.itsconv.cti.call.PendingOutboundRegistry;
import com.itsconv.cti.call.domain.Call;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallControlServiceTest {

    private static final String INTERFACE = "PJSIP/1000";
    private static final String LINKEDID = "1755000000.100";
    private static final String AGENT_CHANNEL = "PJSIP/1000-00000002";

    private AgentSessionRegistry sessions;
    private PendingOutboundRegistry pendingOutbound;
    private CallRegistry calls;
    private RecordingOriginateActions originateActions;
    private RecordingChannelActions channelActions;
    private CallControlService service;

    @BeforeEach
    void setUp() {
        sessions = new AgentSessionRegistry();
        pendingOutbound = new PendingOutboundRegistry();
        calls = new CallRegistry();
        originateActions = new RecordingOriginateActions();
        channelActions = new RecordingChannelActions();
        service = new CallControlService(sessions, pendingOutbound, calls, originateActions, channelActions, new OutboundProperties("from-internal", 15000));
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

    @Test
    void 벨_울리는_상담원_본인의_받기는_talk_NOTIFY를_보냄() {
        loggedIn();
        ringingCall();

        service.answer(LINKEDID, "agent1");

        assertEquals(List.of("notifyTalk " + AGENT_CHANNEL), channelActions.sent);
    }

    @Test
    void 벨이_울리지_않는_콜의_받기는_거부됨() {
        loggedIn();
        Call call = Call.start(LINKEDID, "01012345678", "0212345678");
        calls.put(call);

        assertThrows(IllegalStateException.class, () -> service.answer(LINKEDID, "agent1"));
        assertTrue(channelActions.sent.isEmpty());
    }

    @Test
    void 다른_상담원에게_울리는_콜의_받기는_거부됨() {
        AgentSession other = AgentSession.login(2L, "agent2", "상담원2", "1001", "PJSIP/1001", List.of("queue01"));
        other.unpause();
        sessions.put(other);
        ringingCall();

        assertThrows(IllegalStateException.class, () -> service.answer(LINKEDID, "agent2"));
        assertTrue(channelActions.sent.isEmpty());
    }

    @Test
    void 통화_중인_상담원_본인의_끊기는_상담원_레그를_Hangup함() {
        loggedIn();
        Call call = ringingCall();
        call.connected(INTERFACE, AGENT_CHANNEL);

        service.hangup(LINKEDID, "agent1");

        assertEquals(List.of("hangup " + AGENT_CHANNEL), channelActions.sent);
    }

    @Test
    void CONNECTED가_아닌_콜의_끊기는_거부됨() {
        loggedIn();
        ringingCall();

        assertThrows(IllegalStateException.class, () -> service.hangup(LINKEDID, "agent1"));
        assertTrue(channelActions.sent.isEmpty());
    }

    @Test
    void 없는_콜의_받기와_끊기는_404() {
        loggedIn();

        assertThrows(NoSuchElementException.class, () -> service.answer("nope", "agent1"));
        assertThrows(NoSuchElementException.class, () -> service.hangup("nope", "agent1"));
    }

    @Test
    void 통화_중인_상담원_본인의_보류는_hold_context로_Atxfer함() {
        loggedIn();
        connectedCall();

        service.hold(LINKEDID, "agent1");

        assertEquals(List.of("atxfer %s 1000 hold".formatted(AGENT_CHANNEL)), channelActions.sent);
    }

    @Test
    void CONNECTED가_아닌_콜의_보류는_거부됨() {
        loggedIn();
        ringingCall();

        assertThrows(IllegalStateException.class, () -> service.hold(LINKEDID, "agent1"));
        assertTrue(channelActions.sent.isEmpty());
    }

    @Test
    void 보류_중인_콜의_보류는_거부됨() {
        loggedIn();
        heldCall();

        assertThrows(IllegalStateException.class, () -> service.hold(LINKEDID, "agent1"));
        assertTrue(channelActions.sent.isEmpty());
    }

    @Test
    void 보류_중인_콜의_해제는_CancelAtxfer를_보냄() {
        loggedIn();
        heldCall();

        service.unhold(LINKEDID, "agent1");

        assertEquals(List.of("cancelAtxfer " + AGENT_CHANNEL), channelActions.sent);
    }

    @Test
    void 보류가_아닌_콜의_해제는_거부됨() {
        loggedIn();
        connectedCall();

        assertThrows(IllegalStateException.class, () -> service.unhold(LINKEDID, "agent1"));
        assertTrue(channelActions.sent.isEmpty());
    }

    @Test
    void 보류_중인_콜의_끊기는_거부됨() {
        loggedIn();
        heldCall();

        assertThrows(IllegalStateException.class, () -> service.hangup(LINKEDID, "agent1"));
        assertTrue(channelActions.sent.isEmpty());
    }

    private Call connectedCall() {
        Call call = ringingCall();
        call.connected(INTERFACE, AGENT_CHANNEL);
        return call;
    }

    // bridge 이벤트로 held가 켜진 통화를 재현한다 (ADR-0012)
    private Call heldCall() {
        Call call = connectedCall();
        call.legStarted("agent-leg", AGENT_CHANNEL);
        call.legStarted("hold-leg", "Local/1000@hold-00000001;1");
        call.legEnteredBridge("hold-leg", "bridge-B");
        call.legEnteredBridge("agent-leg", "bridge-B");
        return call;
    }

    private Call ringingCall() {
        Call call = Call.start(LINKEDID, "01012345678", "0212345678");
        call.enqueued("queue01");
        call.agentRinging(INTERFACE, AGENT_CHANNEL);
        calls.put(call);
        return call;
    }

    private AgentSession loggedIn() {
        AgentSession session = AgentSession.login(1L, "agent1", "상담원1", "1000", INTERFACE, List.of("queue01"));
        session.unpause();
        sessions.put(session);
        return session;
    }

    private class RecordingChannelActions extends AmiChannelActions {

        private final List<String> sent = new ArrayList<>();

        private RecordingChannelActions() {
            super(null);
        }

        @Override
        public void notifyTalk(String channel) {
            sent.add("notifyTalk " + channel);
        }

        @Override
        public void hangup(String channel) {
            sent.add("hangup " + channel);
        }

        @Override
        public void atxfer(String channel, String exten, String context) {
            sent.add("atxfer %s %s %s".formatted(channel, exten, context));
        }

        @Override
        public void cancelAtxfer(String channel) {
            sent.add("cancelAtxfer " + channel);
        }
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
