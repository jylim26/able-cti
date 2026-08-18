package com.itsconv.cti.agent;

import com.itsconv.cti.agent.domain.AgentSession;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class AgentSessionRegistry {

    private final ConcurrentHashMap<Long, AgentSession> sessions = new ConcurrentHashMap<>();

    public Optional<AgentSession> find(long agentId) {
        return Optional.ofNullable(sessions.get(agentId));
    }

    public Optional<AgentSession> findByLoginId(String loginId) {
        return loginId == null ? Optional.empty() : sessions.values().stream().filter(s -> loginId.equals(s.getLoginId())).findFirst();
    }

    public Optional<AgentSession> findByInterface(String queueInterface) {
        return queueInterface == null ? Optional.empty() : sessions.values().stream().filter(s -> queueInterface.equals(s.getQueueInterface())).findFirst();
    }

    public List<AgentSession> all() {
        return List.copyOf(sessions.values());
    }

    public void put(AgentSession session) {
        sessions.put(session.getAgentId(), session);
    }

    public void remove(long agentId) {
        sessions.remove(agentId);
    }

    public int size() {
        return sessions.size();
    }
}
