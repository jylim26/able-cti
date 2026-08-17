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

    public AgentSession login(String extension) {
        AgentRecord agent = repository.findByExtension(extension).orElseThrow(() -> new NoSuchElementException("unknown agent extension: %s".formatted(extension)));
        if (registry.find(agent.id()).isPresent()) {
            throw new IllegalStateException("agent %s already logged in".formatted(extension));
        }
        List<String> queues = repository.queuesOf(agent.id());
        if (queues.isEmpty()) {
            throw new IllegalStateException("agent %s has no assigned queues".formatted(extension));
        }
        String queueInterface = "PJSIP/%s".formatted(extension);
        AgentSession session = AgentSession.login(agent.id(), agent.name(), extension, queueInterface, queues);
        registry.put(session);
        try {
            for (String queue : queues) {
                queueActions.addMemberPaused(queue, queueInterface);
                queueActions.pause(queue, queueInterface, PauseReason.LOGIN);
            }
        } catch (RuntimeException e) {
            registry.remove(agent.id());
            throw e;
        }
        log.info("agent login: extension={} queues={} state={} reason={}", extension, queues, session.getStatus(), session.getPauseReason());
        return session;
    }

    public void logout(String extension) {
        AgentSession session = required(extension);
        session.queues().forEach(queue -> queueActions.removeMember(queue, session.getQueueInterface()));
        session.logout();
        registry.remove(session.getAgentId());
        log.info("agent logout: extension={} state={}", extension, session.getStatus());
    }

    public AgentSession pause(String extension, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("pause reason required");
        }
        if (PauseReason.isReserved(reason)) {
            throw new IllegalArgumentException("reserved pause reason: %s".formatted(reason));
        }
        AgentSession session = required(extension);
        session.queues().forEach(queue -> queueActions.pause(queue, session.getQueueInterface(), reason));
        session.pause(reason);
        log.info("agent paused: extension={} reason={} state={}", extension, reason, session.getStatus());
        return session;
    }

    public AgentSession unpause(String extension) {
        AgentSession session = required(extension);
        if (session.getStatus() != AgentStatus.PAUSED) {
            throw new IllegalStateException("agent %s: invalid transition from %s".formatted(extension, session.getStatus()));
        }
        session.queues().forEach(queue -> queueActions.unpause(queue, session.getQueueInterface()));
        session.unpause();
        log.info("agent unpaused: extension={} state={}", extension, session.getStatus());
        return session;
    }

    public List<AgentSession> sessions() {
        return registry.all();
    }

    private AgentSession required(String extension) {
        return registry.findByExtension(extension).orElseThrow(() -> new NoSuchElementException("agent %s not logged in".formatted(extension)));
    }
}
