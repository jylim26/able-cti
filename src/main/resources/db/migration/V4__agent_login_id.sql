-- 사용자와 내선의 분리 (ADR-0007)
-- login_id가 사용자 식별자, extension은 관리자가 사전 매핑하는 내선
ALTER TABLE agents ADD COLUMN login_id varchar(50);

UPDATE agents SET login_id = 'agent1' WHERE extension = '1000';
UPDATE agents SET login_id = 'agent2' WHERE extension = '1001';

ALTER TABLE agents ALTER COLUMN login_id SET NOT NULL;
ALTER TABLE agents ADD CONSTRAINT agents_login_id_key UNIQUE (login_id);
ALTER TABLE agents ALTER COLUMN extension DROP NOT NULL;
