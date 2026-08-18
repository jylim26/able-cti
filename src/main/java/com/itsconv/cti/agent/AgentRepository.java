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

    public Optional<AgentRecord> findByLoginId(String loginId) {
        return jdbcClient.sql("select id, login_id, name, extension from agents where login_id = :loginId").param("loginId", loginId).query(AgentRecord.class).optional();
    }

    public List<String> queuesOf(long agentId) {
        return jdbcClient.sql("select queue_name from agent_queues where agent_id = :agentId order by queue_name").param("agentId", agentId).query(String.class).list();
    }

    public List<String> allQueues() {
        return jdbcClient.sql("select name from queues order by name").query(String.class).list();
    }

    public void replaceQueues(long agentId, List<String> queues) {
        jdbcClient.sql("delete from agent_queues where agent_id = :agentId").param("agentId", agentId).update();
        for (String queue : queues) {
            jdbcClient.sql("insert into agent_queues (agent_id, queue_name) values (:agentId, :queue)").param("agentId", agentId).param("queue", queue).update();
        }
    }

    public record AgentRecord(long id, String loginId, String name, String extension) {
    }
}
