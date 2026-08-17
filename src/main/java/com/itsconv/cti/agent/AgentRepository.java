package com.itsconv.cti.agent;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AgentRepository {

    private final JdbcClient jdbcClient;

    public Optional<AgentRecord> findByExtension(String extension) {
        return jdbcClient.sql("select id, name, extension from agents where extension = :extension").param("extension", extension).query(AgentRecord.class).optional();
    }

    public List<String> queuesOf(long agentId) {
        return jdbcClient.sql("select queue_name from agent_queues where agent_id = :agentId order by queue_name").param("agentId", agentId).query(String.class).list();
    }

    public record AgentRecord(long id, String name, String extension) {
    }
}
