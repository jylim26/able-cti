package com.itsconv.cti.agent.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentSessionTest {

    private AgentSession session;

    @BeforeEach
    void setUp() {
        session = AgentSession.login(1L, "상담원1", "1000", "PJSIP/1000", List.of("queue01"));
    }

    @Test
    void 로그인은_이석_상태로_시작하고_사유는_LOGIN() {
        assertEquals(AgentStatus.PAUSED, session.getStatus());
        assertEquals(PauseReason.LOGIN, session.getPauseReason());
    }

    @Test
    void 이석_해제로_READY가_되고_사유가_지워짐() {
        session.unpause();

        assertEquals(AgentStatus.READY, session.getStatus());
        assertNull(session.getPauseReason());
    }

    @Test
    void READY에서_이석하면_사유가_기록됨() {
        session.unpause();
        session.pause("lunch");

        assertEquals(AgentStatus.PAUSED, session.getStatus());
        assertEquals("lunch", session.getPauseReason());
    }

    @Test
    void 이석_중_사유_변경은_상태를_바꾸지_않음() {
        session.pause("lunch");

        assertEquals(AgentStatus.PAUSED, session.getStatus());
        assertEquals("lunch", session.getPauseReason());
    }

    @Test
    void READY에서_통화가_연결되고_끝나면_READY로_복귀() {
        session.unpause();
        session.callConnected("linkedid-1", "INBOUND");

        assertEquals(AgentStatus.ON_CALL, session.getStatus());
        assertEquals("linkedid-1", session.getCallId());

        session.normalCallEnded();

        assertEquals(AgentStatus.READY, session.getStatus());
        assertNull(session.getCallId());
    }

    @Test
    void 이석_중_통화가_끝나면_통화_전_이석_상태로_복귀() {
        session.pause("lunch");
        session.callConnected("cti-1", "OUTBOUND");

        assertEquals(AgentStatus.ON_CALL, session.getStatus());
        assertNull(session.getPauseReason());

        session.normalCallEnded();

        assertEquals(AgentStatus.PAUSED, session.getStatus());
        assertEquals("lunch", session.getPauseReason());
    }

    @Test
    void 큐_인바운드_통화가_끝나면_후처리로_전환되고_콜_참조가_유지됨() {
        session.unpause();
        session.callConnected("linkedid-1", "INBOUND");
        session.queueInboundCallEnded();

        assertEquals(AgentStatus.PAUSED, session.getStatus());
        assertEquals(PauseReason.ACW, session.getPauseReason());
        assertEquals("linkedid-1", session.getCallId());
    }

    @Test
    void 후처리_해제는_상담원이_직접하고_콜_참조가_지워짐() {
        session.unpause();
        session.callConnected("linkedid-1", "INBOUND");
        session.queueInboundCallEnded();
        session.unpause();

        assertEquals(AgentStatus.READY, session.getStatus());
        assertNull(session.getPauseReason());
        assertNull(session.getCallId());
    }

    @Test
    void 통화_중_이석은_상태를_바꾸지_않고_복귀_목적지만_바꿈() {
        session.unpause();
        session.callConnected("linkedid-1", "INBOUND");
        session.pause("lunch");

        assertEquals(AgentStatus.ON_CALL, session.getStatus());

        session.normalCallEnded();

        assertEquals(AgentStatus.PAUSED, session.getStatus());
        assertEquals("lunch", session.getPauseReason());
    }

    @Test
    void 어느_상태에서든_로그아웃_가능() {
        session.callConnected("linkedid-1", "INBOUND");
        session.logout();

        assertEquals(AgentStatus.LOGGED_OUT, session.getStatus());
        assertNull(session.getPauseReason());
        assertNull(session.getCallId());
        assertNull(session.getCallDirection());
    }

    @Test
    void READY에서_이석_해제는_표에_없는_전이() {
        session.unpause();

        assertThrows(IllegalStateException.class, session::unpause);
    }

    @Test
    void 통화_중_아니면_통화_종료는_표에_없는_전이() {
        assertThrows(IllegalStateException.class, session::normalCallEnded);
    }

    @Test
    void 통화_중_통화_연결은_표에_없는_전이() {
        session.unpause();
        session.callConnected("linkedid-1", "INBOUND");

        assertThrows(IllegalStateException.class, () -> session.callConnected("linkedid-2", "INBOUND"));
    }

}
