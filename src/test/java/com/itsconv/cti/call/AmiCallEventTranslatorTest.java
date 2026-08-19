package com.itsconv.cti.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itsconv.cti.ami.CtiCallStartedEvent;
import com.itsconv.cti.call.domain.Call;
import com.itsconv.cti.call.domain.CallDirection;
import com.itsconv.cti.call.domain.CallState;
import com.itsconv.cti.call.event.CallConnectedEvent;
import com.itsconv.cti.call.event.CallDialingEvent;
import com.itsconv.cti.call.event.CallEndedEvent;
import com.itsconv.cti.call.event.CallRingingCanceledEvent;
import com.itsconv.cti.call.event.CallRingingEvent;
import com.itsconv.cti.call.event.OutboundCallFailedEvent;
import java.util.ArrayList;
import java.util.List;
import org.asteriskjava.manager.event.AgentCalledEvent;
import org.asteriskjava.manager.event.AgentConnectEvent;
import org.asteriskjava.manager.event.AgentRingNoAnswerEvent;
import org.asteriskjava.manager.event.DialBeginEvent;
import org.asteriskjava.manager.event.DialEndEvent;
import org.asteriskjava.manager.event.HangupEvent;
import org.asteriskjava.manager.event.NewChannelEvent;
import org.asteriskjava.manager.event.OriginateResponseEvent;
import org.asteriskjava.manager.event.QueueCallerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AmiCallEventTranslatorTest {

    private static final String LINKEDID = "1755000000.100";
    private static final String CUSTOMER_CHANNEL = "PJSIP/1234-00000001";
    private static final String AGENT_UNIQUEID = "1755000000.101";
    private static final String AGENT_CHANNEL = "PJSIP/1000-00000002";
    private static final String AGENT_INTERFACE = "PJSIP/1000";
    private static final String OUTBOUND_CHANNEL_ID = "cti-spike-b2";
    private static final String OUTBOUND_CUSTOMER_CHANNEL = "PJSIP/1234-0000000a";

    private CallRegistry registry;
    private PendingOutboundRegistry pendingOutbound;
    private AmiCallEventTranslator translator;
    private List<Object> published;

    @BeforeEach
    void setUp() {
        registry = new CallRegistry();
        pendingOutbound = new PendingOutboundRegistry();
        published = new ArrayList<>();
        translator = new AmiCallEventTranslator(registry, pendingOutbound, published::add);
    }

    @Test
    void 추적_표식_없는_콜은_추적하지_않음() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(hangup(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));

        assertEquals(0, registry.size());
    }

    @Test
    void 큐_인바운드_시퀀스로_콜이_조립되고_마지막_채널_종료로_제거됨() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(ctiCallStarted(LINKEDID, CUSTOMER_CHANNEL, "01012345678", "0212345678"));

        Call call = registry.find(LINKEDID).orElseThrow();
        assertEquals("01012345678", call.getCallerNumber());
        assertEquals("0212345678", call.getCalledNumber());
        assertEquals(1, call.legs().size());

        translator.onManagerEvent(queueCallerJoin(LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(newChannel(AGENT_UNIQUEID, LINKEDID, AGENT_CHANNEL));
        translator.onManagerEvent(agentConnect(LINKEDID, AGENT_CHANNEL, AGENT_INTERFACE));

        assertEquals(2, call.legs().size());

        translator.onManagerEvent(hangup(AGENT_UNIQUEID, LINKEDID, AGENT_CHANNEL));
        assertTrue(registry.find(LINKEDID).isPresent());
        assertFalse(call.legs().get(AGENT_UNIQUEID).isAlive());

        translator.onManagerEvent(hangup(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        assertEquals(0, registry.size());
    }

    @Test
    void 정상_통화의_상태_전이() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(ctiCallStarted(LINKEDID, CUSTOMER_CHANNEL, "01012345678", "0212345678"));

        Call call = registry.find(LINKEDID).orElseThrow();
        assertEquals(CallState.RINGING, call.getState());

        translator.onManagerEvent(queueCallerJoin(LINKEDID, CUSTOMER_CHANNEL));
        assertEquals(CallState.QUEUED, call.getState());
        assertEquals("queue01", call.getQueueName());

        translator.onManagerEvent(newChannel(AGENT_UNIQUEID, LINKEDID, AGENT_CHANNEL));
        translator.onManagerEvent(agentCalled(LINKEDID, AGENT_INTERFACE));
        assertEquals(CallState.QUEUED, call.getState());
        assertEquals(AGENT_INTERFACE, call.getRingingAgent());
        assertEquals(AGENT_CHANNEL, call.getRingingChannel());
        assertTrue(published.contains(new CallRingingEvent(LINKEDID, AGENT_INTERFACE, "01012345678", "queue01")));

        translator.onManagerEvent(agentConnect(LINKEDID, AGENT_CHANNEL, AGENT_INTERFACE));
        assertEquals(CallState.CONNECTED, call.getState());
        assertEquals(AGENT_INTERFACE, call.getAgent());
        assertEquals(AGENT_CHANNEL, call.getAgentChannel());
        assertNull(call.getRingingAgent());
        assertNull(call.getRingingChannel());
        assertTrue(call.isAnswered());
        assertTrue(published.contains(new CallConnectedEvent(LINKEDID, AGENT_INTERFACE, "INBOUND")));

        translator.onManagerEvent(hangup(AGENT_UNIQUEID, LINKEDID, AGENT_CHANNEL));
        assertFalse(published.contains(new CallEndedEvent(LINKEDID, AGENT_INTERFACE, true, "INBOUND")));

        translator.onManagerEvent(hangup(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        assertEquals(CallState.ENDED, call.getState());
        assertEquals(0, registry.size());
        assertTrue(published.contains(new CallEndedEvent(LINKEDID, AGENT_INTERFACE, true, "INBOUND")));
    }

    @Test
    void 포기호는_응답_없이_종료됨() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(ctiCallStarted(LINKEDID, CUSTOMER_CHANNEL, "01012345678", "0212345678"));
        translator.onManagerEvent(queueCallerJoin(LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(newChannel(AGENT_UNIQUEID, LINKEDID, AGENT_CHANNEL));
        translator.onManagerEvent(agentCalled(LINKEDID, AGENT_INTERFACE));

        Call call = registry.find(LINKEDID).orElseThrow();
        translator.onManagerEvent(hangup(AGENT_UNIQUEID, LINKEDID, AGENT_CHANNEL));
        translator.onManagerEvent(hangup(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));

        assertEquals(CallState.ENDED, call.getState());
        assertFalse(call.isAnswered());
        assertEquals(0, registry.size());
        assertTrue(published.contains(new CallRingingCanceledEvent(LINKEDID, AGENT_INTERFACE)));
        assertTrue(published.contains(new CallEndedEvent(LINKEDID, null, false, "INBOUND")));
        assertFalse(published.stream().anyMatch(e -> e instanceof CallConnectedEvent));
    }

    @Test
    void 무응답_재분배_동안_상태는_QUEUED로_유지되고_벨울림_기록만_바뀜() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(ctiCallStarted(LINKEDID, CUSTOMER_CHANNEL, "01012345678", "0212345678"));
        translator.onManagerEvent(queueCallerJoin(LINKEDID, CUSTOMER_CHANNEL));

        Call call = registry.find(LINKEDID).orElseThrow();

        translator.onManagerEvent(newChannel("1755000000.101", LINKEDID, "PJSIP/1000-00000002"));
        translator.onManagerEvent(agentCalled(LINKEDID, "PJSIP/1000"));
        assertEquals("PJSIP/1000", call.getRingingAgent());

        translator.onManagerEvent(agentRingNoAnswer(LINKEDID, "PJSIP/1000"));
        translator.onManagerEvent(hangup("1755000000.101", LINKEDID, "PJSIP/1000-00000002"));
        assertEquals(CallState.QUEUED, call.getState());
        assertNull(call.getRingingAgent());
        assertTrue(published.contains(new CallRingingCanceledEvent(LINKEDID, "PJSIP/1000")));

        translator.onManagerEvent(newChannel("1755000000.102", LINKEDID, "PJSIP/1001-00000003"));
        translator.onManagerEvent(agentCalled(LINKEDID, "PJSIP/1001"));
        assertEquals("PJSIP/1001", call.getRingingAgent());

        translator.onManagerEvent(agentRingNoAnswer(LINKEDID, "PJSIP/1001"));
        translator.onManagerEvent(hangup("1755000000.102", LINKEDID, "PJSIP/1001-00000003"));
        assertEquals(CallState.QUEUED, call.getState());

        translator.onManagerEvent(newChannel("1755000000.103", LINKEDID, "PJSIP/1000-00000004"));
        translator.onManagerEvent(agentCalled(LINKEDID, "PJSIP/1000"));
        translator.onManagerEvent(agentConnect(LINKEDID, "PJSIP/1000-00000004", "PJSIP/1000"));

        assertEquals(CallState.CONNECTED, call.getState());
        assertEquals("PJSIP/1000", call.getAgent());
    }

    @Test
    void 표에_없는_전이는_무시되고_상태가_유지됨() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(ctiCallStarted(LINKEDID, CUSTOMER_CHANNEL, "01012345678", "0212345678"));
        translator.onManagerEvent(queueCallerJoin(LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(agentConnect(LINKEDID, AGENT_CHANNEL, AGENT_INTERFACE));

        Call call = registry.find(LINKEDID).orElseThrow();
        assertEquals(CallState.CONNECTED, call.getState());

        translator.onManagerEvent(agentConnect(LINKEDID, AGENT_CHANNEL, AGENT_INTERFACE));
        assertEquals(CallState.CONNECTED, call.getState());

        translator.onManagerEvent(queueCallerJoin(LINKEDID, CUSTOMER_CHANNEL));
        assertEquals(CallState.CONNECTED, call.getState());
    }

    @Test
    void 다른_상담원의_무응답으로는_벨울림_기록이_지워지지_않음() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(ctiCallStarted(LINKEDID, CUSTOMER_CHANNEL, "01012345678", "0212345678"));
        translator.onManagerEvent(queueCallerJoin(LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(agentCalled(LINKEDID, "PJSIP/1001"));

        Call call = registry.find(LINKEDID).orElseThrow();
        translator.onManagerEvent(agentRingNoAnswer(LINKEDID, "PJSIP/1000"));

        assertEquals("PJSIP/1001", call.getRingingAgent());
        assertFalse(published.stream().anyMatch(e -> e instanceof CallRingingCanceledEvent));
    }

    @Test
    void 인바운드가_아닌_방향의_추적_표식은_무시함() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(ctiCallStarted(LINKEDID, CUSTOMER_CHANNEL, "01012345678", "0212345678", "OUTBOUND"));

        assertEquals(0, registry.size());
    }

    @Test
    void 파생_채널의_추적_표식으로는_콜을_만들지_않음() {
        CtiCallStartedEvent event = ctiCallStarted(LINKEDID, AGENT_CHANNEL, "01012345678", "0212345678");
        event.setUniqueId(AGENT_UNIQUEID);
        translator.onManagerEvent(event);

        assertEquals(0, registry.size());
    }

    @Test
    void 아웃바운드_정상_발신_시퀀스() {
        pendingOutbound.register(OUTBOUND_CHANNEL_ID, "agent1", AGENT_INTERFACE, "1000", "01012345678");

        translator.onManagerEvent(newChannel(OUTBOUND_CHANNEL_ID, OUTBOUND_CHANNEL_ID, AGENT_CHANNEL));

        Call call = registry.find(OUTBOUND_CHANNEL_ID).orElseThrow();
        assertEquals(CallDirection.OUTBOUND, call.getDirection());
        assertEquals(CallState.RINGING, call.getState());
        assertEquals(AGENT_INTERFACE, call.getAgent());
        assertEquals("01012345678", call.getCalledNumber());
        assertEquals(AGENT_CHANNEL, call.getAgentChannel());
        assertEquals(1, call.legs().size());

        translator.onManagerEvent(dialBegin(OUTBOUND_CHANNEL_ID, null, "dev-1787047478.10"));
        assertFalse(published.stream().anyMatch(e -> e instanceof CallDialingEvent));

        translator.onManagerEvent(newChannel("dev-1787047478.10", OUTBOUND_CHANNEL_ID, OUTBOUND_CUSTOMER_CHANNEL));
        assertEquals(2, call.legs().size());
        assertEquals(CallState.RINGING, call.getState());

        translator.onManagerEvent(dialBegin(OUTBOUND_CHANNEL_ID, AGENT_CHANNEL, "dev-1787047478.10"));
        assertTrue(published.contains(new CallDialingEvent(OUTBOUND_CHANNEL_ID, AGENT_INTERFACE, "01012345678")));

        translator.onManagerEvent(dialEnd(OUTBOUND_CHANNEL_ID, "dev-1787047478.10", "ANSWER"));
        assertEquals(CallState.CONNECTED, call.getState());
        assertTrue(call.isAnswered());
        assertTrue(published.contains(new CallConnectedEvent(OUTBOUND_CHANNEL_ID, AGENT_INTERFACE, "OUTBOUND")));

        translator.onManagerEvent(hangup("dev-1787047478.10", OUTBOUND_CHANNEL_ID, OUTBOUND_CUSTOMER_CHANNEL));
        translator.onManagerEvent(hangup(OUTBOUND_CHANNEL_ID, OUTBOUND_CHANNEL_ID, AGENT_CHANNEL));

        assertEquals(CallState.ENDED, call.getState());
        assertEquals(0, registry.size());
        assertTrue(published.contains(new CallEndedEvent(OUTBOUND_CHANNEL_ID, AGENT_INTERFACE, true, "OUTBOUND")));
    }

    @Test
    void 상담원_레그의_DialEnd는_소스_linkedid가_없어_무시됨() {
        pendingOutbound.register(OUTBOUND_CHANNEL_ID, "agent1", AGENT_INTERFACE, "1000", "01012345678");
        translator.onManagerEvent(newChannel(OUTBOUND_CHANNEL_ID, OUTBOUND_CHANNEL_ID, AGENT_CHANNEL));

        translator.onManagerEvent(dialEnd(null, OUTBOUND_CHANNEL_ID, "ANSWER"));

        Call call = registry.find(OUTBOUND_CHANNEL_ID).orElseThrow();
        assertEquals(CallState.RINGING, call.getState());
        assertFalse(published.stream().anyMatch(e -> e instanceof CallConnectedEvent));
    }

    @Test
    void 아웃바운드_응답_후의_DialBegin은_DIALING을_만들지_않음() {
        pendingOutbound.register(OUTBOUND_CHANNEL_ID, "agent1", AGENT_INTERFACE, "1000", "01012345678");
        translator.onManagerEvent(newChannel(OUTBOUND_CHANNEL_ID, OUTBOUND_CHANNEL_ID, AGENT_CHANNEL));
        translator.onManagerEvent(newChannel("dev-1787047478.10", OUTBOUND_CHANNEL_ID, OUTBOUND_CUSTOMER_CHANNEL));
        translator.onManagerEvent(dialEnd(OUTBOUND_CHANNEL_ID, "dev-1787047478.10", "ANSWER"));

        translator.onManagerEvent(dialBegin(OUTBOUND_CHANNEL_ID, AGENT_CHANNEL, "dev-1787047478.99"));

        assertFalse(published.stream().anyMatch(e -> e instanceof CallDialingEvent));
    }

    @Test
    void 인바운드_콜의_DialBegin은_DIALING을_만들지_않음() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(ctiCallStarted(LINKEDID, CUSTOMER_CHANNEL, "01012345678", "0212345678"));
        translator.onManagerEvent(queueCallerJoin(LINKEDID, CUSTOMER_CHANNEL));

        translator.onManagerEvent(dialBegin(LINKEDID, CUSTOMER_CHANNEL, AGENT_UNIQUEID));

        assertFalse(published.stream().anyMatch(e -> e instanceof CallDialingEvent));
    }

    @Test
    void 인바운드_콜의_DialEnd로는_전이하지_않음() {
        translator.onManagerEvent(newChannel(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        translator.onManagerEvent(ctiCallStarted(LINKEDID, CUSTOMER_CHANNEL, "01012345678", "0212345678"));
        translator.onManagerEvent(queueCallerJoin(LINKEDID, CUSTOMER_CHANNEL));

        translator.onManagerEvent(dialEnd(LINKEDID, AGENT_UNIQUEID, "ANSWER"));

        Call call = registry.find(LINKEDID).orElseThrow();
        assertEquals(CallState.QUEUED, call.getState());
        assertFalse(published.stream().anyMatch(e -> e instanceof CallConnectedEvent));
    }

    @Test
    void 아웃바운드_상담원_무응답은_응답_없이_종료됨() {
        pendingOutbound.register(OUTBOUND_CHANNEL_ID, "agent1", AGENT_INTERFACE, "1000", "01012345678");
        translator.onManagerEvent(newChannel(OUTBOUND_CHANNEL_ID, OUTBOUND_CHANNEL_ID, AGENT_CHANNEL));

        translator.onManagerEvent(originateResponse(OUTBOUND_CHANNEL_ID, false, 3));
        translator.onManagerEvent(hangup(OUTBOUND_CHANNEL_ID, OUTBOUND_CHANNEL_ID, AGENT_CHANNEL));

        assertEquals(0, registry.size());
        assertTrue(published.contains(new CallEndedEvent(OUTBOUND_CHANNEL_ID, AGENT_INTERFACE, false, "OUTBOUND")));
        assertFalse(published.stream().anyMatch(e -> e instanceof OutboundCallFailedEvent));
    }

    @Test
    void 채널_생성_전_발신_실패는_실패_이벤트로_알림() {
        pendingOutbound.register(OUTBOUND_CHANNEL_ID, "agent1", AGENT_INTERFACE, "1000", "01012345678");

        translator.onManagerEvent(originateResponse(OUTBOUND_CHANNEL_ID, false, 0));

        assertEquals(0, registry.size());
        assertTrue(published.contains(new OutboundCallFailedEvent(OUTBOUND_CHANNEL_ID, "agent1", 0)));
        assertTrue(pendingOutbound.consume(OUTBOUND_CHANNEL_ID).isEmpty());
    }

    @Test
    void 발신_성공_응답으로는_실패_이벤트를_만들지_않음() {
        pendingOutbound.register(OUTBOUND_CHANNEL_ID, "agent1", AGENT_INTERFACE, "1000", "01012345678");
        translator.onManagerEvent(newChannel(OUTBOUND_CHANNEL_ID, OUTBOUND_CHANNEL_ID, AGENT_CHANNEL));

        translator.onManagerEvent(originateResponse(OUTBOUND_CHANNEL_ID, true, 4));

        assertFalse(published.stream().anyMatch(e -> e instanceof OutboundCallFailedEvent));
    }

    private NewChannelEvent newChannel(String uniqueId, String linkedid, String channel) {
        NewChannelEvent event = new NewChannelEvent(this);
        event.setUniqueId(uniqueId);
        event.setLinkedid(linkedid);
        event.setChannel(channel);
        return event;
    }

    private CtiCallStartedEvent ctiCallStarted(String linkedid, String channel, String callerIdNum, String exten) {
        return ctiCallStarted(linkedid, channel, callerIdNum, exten, "INBOUND");
    }

    private CtiCallStartedEvent ctiCallStarted(String linkedid, String channel, String callerIdNum, String exten, String direction) {
        CtiCallStartedEvent event = new CtiCallStartedEvent(this);
        event.setLinkedid(linkedid);
        event.setUniqueId(linkedid);
        event.setChannel(channel);
        event.setCallerIdNum(callerIdNum);
        event.setExten(exten);
        event.setDirection(direction);
        return event;
    }

    private QueueCallerJoinEvent queueCallerJoin(String linkedid, String channel) {
        QueueCallerJoinEvent event = new QueueCallerJoinEvent(this);
        event.setLinkedId(linkedid);
        event.setUniqueId(linkedid);
        event.setChannel(channel);
        event.setQueue("queue01");
        event.setPosition(1);
        return event;
    }

    private AgentCalledEvent agentCalled(String linkedid, String agentInterface) {
        AgentCalledEvent event = new AgentCalledEvent(this);
        event.setLinkedId(linkedid);
        event.setUniqueId(linkedid);
        event.setChannel(CUSTOMER_CHANNEL);
        event.setInterface(agentInterface);
        event.setDestChannel(agentInterface + "-00000002");
        return event;
    }

    private AgentRingNoAnswerEvent agentRingNoAnswer(String linkedid, String agentInterface) {
        AgentRingNoAnswerEvent event = new AgentRingNoAnswerEvent(this);
        event.setLinkedId(linkedid);
        event.setUniqueId(linkedid);
        event.setChannel(CUSTOMER_CHANNEL);
        event.setInterface(agentInterface);
        return event;
    }

    private AgentConnectEvent agentConnect(String linkedid, String channel, String agentInterface) {
        AgentConnectEvent event = new AgentConnectEvent(this);
        event.setLinkedId(linkedid);
        event.setUniqueId(linkedid);
        event.setChannel(channel);
        event.setInterface(agentInterface);
        event.setDestChannel(channel);
        return event;
    }

    private HangupEvent hangup(String uniqueId, String linkedid, String channel) {
        HangupEvent event = new HangupEvent(this);
        event.setUniqueId(uniqueId);
        event.setLinkedId(linkedid);
        event.setChannel(channel);
        return event;
    }

    private DialBeginEvent dialBegin(String linkedid, String channel, String destUniqueId) {
        DialBeginEvent event = new DialBeginEvent(this);
        event.setLinkedId(linkedid);
        event.setChannel(channel);
        event.setDestUniqueId(destUniqueId);
        return event;
    }

    private DialEndEvent dialEnd(String linkedid, String destUniqueId, String dialStatus) {
        DialEndEvent event = new DialEndEvent(this);
        event.setLinkedId(linkedid);
        event.setDestUniqueId(destUniqueId);
        event.setDialStatus(dialStatus);
        return event;
    }

    private OriginateResponseEvent originateResponse(String uniqueId, boolean success, int reason) {
        OriginateResponseEvent event = new OriginateResponseEvent(this);
        event.setResponse(success ? "Success" : "Failure");
        event.setUniqueId(uniqueId);
        event.setReason(reason);
        return event;
    }
}
