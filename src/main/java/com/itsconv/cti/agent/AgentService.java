package com.itsconv.cti.agent;

import com.itsconv.cti.agent.AgentRepository.AgentRecord;
import com.itsconv.cti.agent.domain.AgentSession;
import com.itsconv.cti.agent.domain.AgentStatus;
import com.itsconv.cti.agent.domain.PauseReason;
import com.itsconv.cti.ami.AmiQueueActions;
import java.util.List;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentRepository repository;
    private final AgentSessionRegistry registry;
    private final AmiQueueActions queueActions;

    public AgentSession login(String loginId) {
        AgentRecord agent = repository.findByLoginId(loginId).orElseThrow(() -> new NoSuchElementException("unknown agent: %s".formatted(loginId)));
        if (agent.extension() == null) {
            throw new IllegalStateException("agent %s has no mapped extension".formatted(loginId));
        }
        if (registry.find(agent.id()).isPresent()) {
            throw new IllegalStateException("agent %s already logged in".formatted(loginId));
        }
        List<String> queues = repository.queuesOf(agent.id());
        if (queues.isEmpty()) {
            throw new IllegalStateException("agent %s has no assigned queues".formatted(loginId));
        }
        String queueInterface = "PJSIP/%s".formatted(agent.extension());
        AgentSession session = AgentSession.login(agent.id(), agent.loginId(), agent.name(), agent.extension(), queueInterface, queues);
        registry.put(session);
        try {
            for (String queue : queues) {
                queueActions.addMemberPaused(queue, queueInterface, agent.loginId());
                queueActions.pause(queue, queueInterface, PauseReason.LOGIN);
            }
        } catch (RuntimeException e) {
            registry.remove(agent.id());
            throw e;
        }
        log.info("agent login: loginId={} extension={} queues={} state={} reason={}", loginId, agent.extension(), queues, session.getStatus(), session.getPauseReason());
        return session;
    }

    public void logout(String loginId) {
        AgentSession session = required(loginId);
        session.queues().forEach(queue -> queueActions.removeMember(queue, session.getQueueInterface()));
        session.logout();
        registry.remove(session.getAgentId());
        log.info("agent logout: loginId={} state={}", loginId, session.getStatus());
    }

    public AgentSession pause(String loginId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("pause reason required");
        }
        if (PauseReason.isReserved(reason)) {
            throw new IllegalArgumentException("reserved pause reason: %s".formatted(reason));
        }
        AgentSession session = required(loginId);
        session.queues().forEach(queue -> queueActions.pause(queue, session.getQueueInterface(), reason));
        session.pause(reason);
        log.info("agent paused: loginId={} reason={} state={}", loginId, reason, session.getStatus());
        return session;
    }

    public AgentSession unpause(String loginId) {
        AgentSession session = required(loginId);
        if (session.getStatus() != AgentStatus.PAUSED) {
            throw new IllegalStateException("agent %s: invalid transition from %s".formatted(loginId, session.getStatus()));
        }
        session.queues().forEach(queue -> queueActions.unpause(queue, session.getQueueInterface()));
        session.unpause();
        log.info("agent unpaused: loginId={} state={}", loginId, session.getStatus());
        return session;
    }

    public void callConnected(String queueInterface, String callId, String direction) {
        registry.findByInterface(queueInterface).ifPresent(session -> {
            session.callConnected(callId, direction);
            log.info("agent on call: loginId={} callId={} state={}", session.getLoginId(), callId, session.getStatus());
        });
    }

    public void queueInboundCallEnded(String queueInterface) {
        registry.findByInterface(queueInterface).ifPresent(session -> {
            session.queueInboundCallEnded();
            session.queues().forEach(queue -> queueActions.pause(queue, session.getQueueInterface(), PauseReason.ACW));
            log.info("agent acw: loginId={} callId={} state={} reason={}", session.getLoginId(), session.getCallId(), session.getStatus(), session.getPauseReason());
        });
    }

    public List<AgentSession> sessions() {
        return registry.all();
    }

    private AgentSession required(String loginId) {
        return registry.findByLoginId(loginId).orElseThrow(() -> new NoSuchElementException("agent %s not logged in".formatted(loginId)));
    }
}
