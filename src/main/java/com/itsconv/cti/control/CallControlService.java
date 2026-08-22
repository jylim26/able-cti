package com.itsconv.cti.control;

import com.itsconv.cti.agent.AgentSessionRegistry;
import com.itsconv.cti.agent.domain.AgentSession;
import com.itsconv.cti.agent.domain.AgentStatus;
import com.itsconv.cti.agent.domain.PauseReason;
import com.itsconv.cti.ami.AmiChannelActions;
import com.itsconv.cti.ami.AmiOriginateActions;
import com.itsconv.cti.call.CallRegistry;
import com.itsconv.cti.call.PendingOutboundRegistry;
import com.itsconv.cti.call.domain.Call;
import com.itsconv.cti.call.domain.CallState;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallControlService {

    private static final String HOLD_CONTEXT = "hold";

    private final AgentSessionRegistry sessions;
    private final PendingOutboundRegistry pendingOutbound;
    private final CallRegistry calls;
    private final AmiOriginateActions originateActions;
    private final AmiChannelActions channelActions;
    private final OutboundProperties properties;

    public String clickToCall(String loginId, String number) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("number required");
        }
        AgentSession session = sessions.findByLoginId(loginId).orElseThrow(() -> new NoSuchElementException("agent %s not logged in".formatted(loginId)));
        if (session.getStatus() != AgentStatus.PAUSED || !PauseReason.OUTBOUND.equals(session.getPauseReason())) {
            throw new IllegalStateException("agent %s must be paused with reason %s to make a call".formatted(loginId, PauseReason.OUTBOUND));
        }
        String channelId = "cti-" + UUID.randomUUID();
        pendingOutbound.register(channelId, loginId, session.getQueueInterface(), session.getExtension(), number);
        try {
            originateActions.originate(session.getQueueInterface(), channelId, properties.context(), number, properties.ringTimeoutMs());
        } catch (RuntimeException e) {
            pendingOutbound.remove(channelId);
            throw e;
        }
        log.info("originate sent: loginId={} number={} callId={}", loginId, number, channelId);
        return channelId;
    }

    // 벨이 울리는 상담원 본인만 받을 수 있다 (ADR-0011)
    public void answer(String callId, String loginId) {
        Call call = requireCall(callId);
        AgentSession session = requireSession(loginId);
        String channel = call.getRingingChannel();
        if (channel == null || !session.getQueueInterface().equals(call.getRingingAgent())) {
            throw new IllegalStateException("call %s is not ringing for agent %s".formatted(callId, loginId));
        }
        channelActions.notifyTalk(channel);
        log.info("answer notify sent: callId={} loginId={} channel={}", callId, loginId, channel);
    }

    // 통화 중인 상담원 본인만, 상담원 레그만 끊는다 (ADR-0011)
    public void hangup(String callId, String loginId) {
        Call call = requireConnectedFor(callId, loginId);
        // 보류 중 상담원 레그를 끊으면 고객이 가드 타임아웃까지 대기음에 갇힌다 (ADR-0012)
        if (call.isHeld()) {
            throw new IllegalStateException("call %s is held; unhold first".formatted(callId));
        }
        channelActions.hangup(call.getAgentChannel());
        log.info("hangup sent: callId={} loginId={} channel={}", callId, loginId, call.getAgentChannel());
    }

    // 보류는 hold context로의 협의 전환. 확정은 bridge 이벤트가 판정한다 (ADR-0012)
    public void hold(String callId, String loginId) {
        Call call = requireConnectedFor(callId, loginId);
        if (call.isHeld()) {
            throw new IllegalStateException("call %s is already held".formatted(callId));
        }
        AgentSession session = requireSession(loginId);
        channelActions.atxfer(call.getAgentChannel(), session.getExtension(), HOLD_CONTEXT);
        log.info("hold sent: callId={} loginId={} channel={}", callId, loginId, call.getAgentChannel());
    }

    public void unhold(String callId, String loginId) {
        Call call = requireConnectedFor(callId, loginId);
        if (!call.isHeld()) {
            throw new IllegalStateException("call %s is not held".formatted(callId));
        }
        channelActions.cancelAtxfer(call.getAgentChannel());
        log.info("unhold sent: callId={} loginId={} channel={}", callId, loginId, call.getAgentChannel());
    }

    private Call requireConnectedFor(String callId, String loginId) {
        Call call = requireCall(callId);
        AgentSession session = requireSession(loginId);
        if (call.getState() != CallState.CONNECTED || !session.getQueueInterface().equals(call.getAgent())) {
            throw new IllegalStateException("call %s is not connected for agent %s".formatted(callId, loginId));
        }
        return call;
    }

    private Call requireCall(String callId) {
        return calls.find(callId).orElseThrow(() -> new NoSuchElementException("call not found: %s".formatted(callId)));
    }

    private AgentSession requireSession(String loginId) {
        return sessions.findByLoginId(loginId).orElseThrow(() -> new NoSuchElementException("agent %s not logged in".formatted(loginId)));
    }
}
