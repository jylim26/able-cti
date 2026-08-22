package com.itsconv.cti.call;

import com.itsconv.cti.ami.CtiCallStartedEvent;
import com.itsconv.cti.call.domain.Call;
import com.itsconv.cti.call.domain.CallDirection;
import com.itsconv.cti.call.domain.CallState;
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
import org.asteriskjava.manager.ManagerEventListener;
import org.asteriskjava.manager.event.AgentCalledEvent;
import org.asteriskjava.manager.event.AgentConnectEvent;
import org.asteriskjava.manager.event.AgentRingNoAnswerEvent;
import org.asteriskjava.manager.event.BridgeEnterEvent;
import org.asteriskjava.manager.event.BridgeLeaveEvent;
import org.asteriskjava.manager.event.DialBeginEvent;
import org.asteriskjava.manager.event.DialEndEvent;
import org.asteriskjava.manager.event.DialEvent;
import org.asteriskjava.manager.event.HangupEvent;
import org.asteriskjava.manager.event.ManagerEvent;
import org.asteriskjava.manager.event.NewChannelEvent;
import org.asteriskjava.manager.event.OriginateResponseEvent;
import org.asteriskjava.manager.event.QueueCallerJoinEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AmiCallEventTranslator implements ManagerEventListener {

    private static final String INBOUND = "INBOUND";

    private final CallRegistry registry;
    private final PendingOutboundRegistry pendingOutbound;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void onManagerEvent(ManagerEvent event) {
        try {
            switch (event) {
                case CtiCallStartedEvent e -> onCtiCallStarted(e);
                case NewChannelEvent e -> onNewChannel(e);
                case QueueCallerJoinEvent e -> onQueueCallerJoin(e);
                case AgentCalledEvent e -> onAgentCalled(e);
                case AgentRingNoAnswerEvent e -> onAgentRingNoAnswer(e);
                case AgentConnectEvent e -> onAgentConnect(e);
                case DialBeginEvent e -> onDialBegin(e);
                case DialEndEvent e -> onDialEnd(e);
                case OriginateResponseEvent e -> onOriginateResponse(e);
                case BridgeEnterEvent e -> onBridgeEnter(e);
                case BridgeLeaveEvent e -> onBridgeLeave(e);
                case HangupEvent e -> onHangup(e);
                default -> { }
            }
        } catch (IllegalStateException e) {
            log.warn("ignored AMI event {}: {}", event.getClass().getSimpleName(), e.getMessage());
        } catch (Exception e) {
            // 개별 이벤트 처리 실패가 AMI 이벤트 수신 전체를 중단시키지 않도록 한다.
            log.error("failed to handle AMI event {}", event.getClass().getSimpleName(), e);
        }
    }

    private void onCtiCallStarted(CtiCallStartedEvent e) {
        String linkedid = e.getLinkedid();

        // 최초 채널에서 발생한 추적 대상 이벤트만 통화 시작으로 처리한다.
        if (linkedid == null || !linkedid.equals(e.getUniqueId()) || registry.find(linkedid).isPresent()) {
            return;
        }

        // CtiCallStartedEvent는 인바운드 통화 시작 표식으로만 사용한다.
        if (!INBOUND.equals(e.getDirection())) {
            log.warn("ignored CtiCallStarted with direction {} for {}", e.getDirection(), linkedid);
            return;
        }

        Call call = Call.start(linkedid, e.getCallerIdNum(), e.getExten());
        call.legStarted(e.getUniqueId(), e.getChannel());
        registry.put(call);

        log.info("call started: linkedid={} caller={} called={} state={}", linkedid, call.getCallerNumber(), call.getCalledNumber(), call.getState());
    }

    private void onNewChannel(NewChannelEvent e) {
        // 클릭투콜 최초 채널이면 pending 요청을 실제 아웃바운드 통화로 전환한다.
        if (e.getUniqueId() != null && e.getUniqueId().equals(e.getLinkedid()) && startOutbound(e)) {
            return;
        }

        // 이미 추적 중인 통화에서 파생된 채널을 통화 레그로 등록한다.
        registry.find(e.getLinkedid()).ifPresent(call -> {
            call.legStarted(e.getUniqueId(), e.getChannel());
            log.info("leg started: linkedid={} channel={}", call.getLinkedid(), e.getChannel());
        });
    }

    private boolean startOutbound(NewChannelEvent e) {
        // pending 클릭투콜 요청을 실제 아웃바운드 Call로 전환한다.
        return pendingOutbound.consume(e.getUniqueId()).map(pending -> {
            Call call = Call.startOutbound(e.getLinkedid(), pending.agentInterface(), pending.agentExtension(), pending.customerNumber(), e.getChannel());
            call.legStarted(e.getUniqueId(), e.getChannel());
            registry.put(call);
            log.info("call started: linkedid={} direction=OUTBOUND agent={} called={} state={}", call.getLinkedid(), call.getAgent(), call.getCalledNumber(), call.getState());
            return true;
        }).orElse(false);
    }

    private void onQueueCallerJoin(QueueCallerJoinEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> {
            call.enqueued(e.getQueue());
            log.info("queue joined: linkedid={} queue={} position={} state={}", call.getLinkedid(), e.getQueue(), e.getPosition(), call.getState());
        });
    }

    private void onAgentCalled(AgentCalledEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> {
            call.agentRinging(e.getInterface(), e.getDestChannel());
            log.info("agent ringing: linkedid={} interface={} state={}", call.getLinkedid(), e.getInterface(), call.getState());

            // 인바운드 통화가 분배된 상담원에게 RINGING을 알린다.
            eventPublisher.publishEvent(new CallRingingEvent(call.getLinkedid(), e.getInterface(), call.getCallerNumber(), call.getQueueName()));
        });
    }

    private void onAgentRingNoAnswer(AgentRingNoAnswerEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> {
            // 현재 벨이 울리는 상담원의 무응답 이벤트일 때만 취소를 알린다.
            if (call.agentRingCanceled(e.getInterface())) {
                eventPublisher.publishEvent(new CallRingingCanceledEvent(call.getLinkedid(), e.getInterface()));
            }
            log.info("agent ring no answer: linkedid={} interface={} state={}", call.getLinkedid(), e.getInterface(), call.getState());
        });
    }

    private void onAgentConnect(AgentConnectEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> {
            call.connected(e.getInterface(), e.getDestChannel());
            log.info("agent connected: linkedid={} interface={} state={}", call.getLinkedid(), e.getInterface(), call.getState());
            eventPublisher.publishEvent(new CallConnectedEvent(call.getLinkedid(), e.getInterface(), call.getDirection().name()));
        });
    }

    private void onDialBegin(DialBeginEvent e) {
        // 소스 채널이 없는 DialBegin은 고객 발신 시작 이벤트로 처리하지 않는다.
        if (e.getChannel() == null) {
            return;
        }
        registry.find(e.getLinkedId()).ifPresent(call -> {
            // 아웃바운드 연결 전에 발생한 이벤트만 DIALING으로 알린다.
            if (call.getDirection() != CallDirection.OUTBOUND || call.getState() != CallState.RINGING) {
                return;
            }
            log.info("customer dialing: linkedid={} called={}", call.getLinkedid(), call.getCalledNumber());
            eventPublisher.publishEvent(new CallDialingEvent(call.getLinkedid(), call.getAgent(), call.getCalledNumber()));
        });
    }

    private void onDialEnd(DialEndEvent e) {
        // 아웃바운드 고객이 실제로 응답한 경우만 연결 완료로 처리한다.
        if (!DialEvent.DIALSTATUS_ANSWER.equals(e.getDialStatus())) {
            return;
        }
        registry.find(e.getLinkedId()).ifPresent(call -> {
            if (call.getDirection() != CallDirection.OUTBOUND) {
                return;
            }
            call.customerAnswered();
            log.info("customer answered: linkedid={} agent={} state={}", call.getLinkedid(), call.getAgent(), call.getState());
            eventPublisher.publishEvent(new CallConnectedEvent(call.getLinkedid(), call.getAgent(), call.getDirection().name()));
        });
    }

    private void onOriginateResponse(OriginateResponseEvent e) {
        // Originate 성공은 발신 요청 접수이며, 실제 고객 응답은 DialEnd에서 처리한다.
        if (e.isSuccess()) {
            return;
        }
        
        // 채널 생성 전에 실패한 클릭투콜 요청만 명시적인 발신 실패로 알린다.
        pendingOutbound.consume(e.getUniqueId()).ifPresent(pending -> {
            log.warn("originate failed: callId={} loginId={} reason={}", e.getUniqueId(), pending.loginId(), e.getReason());
            eventPublisher.publishEvent(new OutboundCallFailedEvent(e.getUniqueId(), pending.loginId(), e.getReason()));
        });
    }

    private void onBridgeEnter(BridgeEnterEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> {
            if (call.legEnteredBridge(e.getUniqueId(), e.getBridgeUniqueId())) {
                publishHeldChange(call);
            }
        });
    }

    private void onBridgeLeave(BridgeLeaveEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> {
            if (call.legLeftBridge(e.getUniqueId(), e.getBridgeUniqueId())) {
                publishHeldChange(call);
            }
        });
    }

    // 보류 확정은 단일 이벤트가 아니라 bridge 구성 변화로 판정한다 (ADR-0012)
    private void publishHeldChange(Call call) {
        log.info("call {}: linkedid={} agent={}", call.isHeld() ? "held" : "resumed", call.getLinkedid(), call.getAgent());
        if (call.isHeld()) {
            eventPublisher.publishEvent(new CallHeldEvent(call.getLinkedid(), call.getAgent()));
        } else {
            eventPublisher.publishEvent(new CallResumedEvent(call.getLinkedid(), call.getAgent()));
        }
    }

    private void onHangup(HangupEvent e) {
        registry.find(e.getLinkedId()).ifPresent(call -> {
            boolean lastLegEnded = call.legEnded(e.getUniqueId());
            log.info("leg ended: linkedid={} channel={}", call.getLinkedid(), e.getChannel());
            
            // 일부 채널만 종료된 경우에는 전체 통화를 종료하지 않는다.
            if (!lastLegEnded) {
                return;
            }

            registry.remove(call.getLinkedid());
            log.info("call ended: linkedid={} state={} answered={}", call.getLinkedid(), call.getState(), call.isAnswered());

            // 상담원 벨이 울리는 중 통화가 종료되면 화면의 RINGING을 먼저 해제한다.
            if (call.getRingingAgent() != null) {
                eventPublisher.publishEvent(new CallRingingCanceledEvent(call.getLinkedid(), call.getRingingAgent()));
            }
            eventPublisher.publishEvent(new CallEndedEvent(call.getLinkedid(), call.getAgent(), call.isAnswered(), call.getDirection().name()));
        });
    }
}
