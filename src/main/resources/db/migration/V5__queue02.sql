-- 로그인 시 큐 선택(멀티)을 테스트할 두 번째 큐
INSERT INTO queues (name) VALUES ('queue02');
INSERT INTO agent_queues (agent_id, queue_name) SELECT id, 'queue02' FROM agents;
