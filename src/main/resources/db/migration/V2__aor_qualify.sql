-- 내선 도달성 유지: Asterisk가 30초마다 OPTIONS로 contact 생사를 확인한다.
-- 프록시/NAT 뒤의 소프트폰은 트래픽이 없으면 경로 매핑이 사라져
-- 등록은 남아 있는데 벨이 가지 않는 상태가 된다. qualify가 경로를 계속 살려둔다.
UPDATE ps_aors SET qualify_frequency = 30;
