CREATE TABLE agents (
    id        bigserial    PRIMARY KEY,
    name      varchar(100) NOT NULL,
    extension varchar(20)  NOT NULL UNIQUE
);

CREATE TABLE agent_queues (
    agent_id   bigint       NOT NULL REFERENCES agents (id),
    queue_name varchar(128) NOT NULL,
    PRIMARY KEY (agent_id, queue_name)
);

-- 개발용 상담원. 내선 1000·1001을 queue01에 배정한다
INSERT INTO agents (name, extension) VALUES ('상담원1', '1000'), ('상담원2', '1001');
INSERT INTO agent_queues (agent_id, queue_name) SELECT id, 'queue01' FROM agents;
