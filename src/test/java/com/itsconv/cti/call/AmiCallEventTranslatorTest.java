package com.itsconv.cti.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itsconv.cti.ami.CtiCallStartedEvent;
import com.itsconv.cti.call.domain.Call;
import com.itsconv.cti.call.domain.CallState;
import org.asteriskjava.manager.event.AgentCalledEvent;
import org.asteriskjava.manager.event.AgentConnectEvent;
import org.asteriskjava.manager.event.AgentRingNoAnswerEvent;
import org.asteriskjava.manager.event.HangupEvent;
import org.asteriskjava.manager.event.NewChannelEvent;
import org.asteriskjava.manager.event.QueueCallerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AmiCallEventTranslatorTest {

    private static final String LINKEDID = "1755000000.100";
    private static final String CUSTOMER_CHANNEL = "PJSIP/1234-00000001";
    private static final String AGENT_UNIQUEID = "1755000000.101";
    private static final String AGENT_CHANNEL = "PJSIP/1000-00000002";
    private static final String AGENT_INTERFACE = "PJSIP/1000";

    private CallRegistry registry;
    private AmiCallEventTranslator translator;

    @BeforeEach
    void setUp() {
        registry = new CallRegistry();
        translator = new AmiCallEventTranslator(registry);
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

        translator.onManagerEvent(agentConnect(LINKEDID, AGENT_CHANNEL, AGENT_INTERFACE));
        assertEquals(CallState.CONNECTED, call.getState());
        assertEquals(AGENT_INTERFACE, call.getAgent());
        assertNull(call.getRingingAgent());
        assertTrue(call.isAnswered());

        translator.onManagerEvent(hangup(AGENT_UNIQUEID, LINKEDID, AGENT_CHANNEL));
        translator.onManagerEvent(hangup(LINKEDID, LINKEDID, CUSTOMER_CHANNEL));
        assertEquals(CallState.ENDED, call.getState());
        assertEquals(0, registry.size());
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
        return event;
    }

    private HangupEvent hangup(String uniqueId, String linkedid, String channel) {
        HangupEvent event = new HangupEvent(this);
        event.setUniqueId(uniqueId);
        event.setLinkedId(linkedid);
        event.setChannel(channel);
        return event;
    }
}
