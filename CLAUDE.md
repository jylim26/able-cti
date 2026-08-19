# CLAUDE.md

Asterisk 기반 콜센터 CTI 서버. 설계를 하나씩 결정하고 ADR로 남기면서 만든다.

작업을 시작하기 전에 이 문서의 "현재 상태"를 먼저 읽는다.
코드나 설계를 바꿨으면 끝나기 전에 이 문서를 갱신한다.

기능을 개발할 때 MVP 단계에서는 과한 방어 코드를 작성하지 않는다.
지금 일어날 수 있는 실패만 다루고, 추측성 예외 처리는 넣지 않는다.

---

## 문서

docs 하위 폴더는 성격으로 나뉜다. 새 문서는 성격에 맞는 폴더에 넣는다.

| 폴더 | 성격 | 언제 읽는가 |
|---|---|---|
| `docs/rules/` | 지켜야 할 규칙 (코딩 컨벤션 등) | 코드 작성 전 |
| `docs/adr/` | 설계 결정 기록. 무엇을, 왜 그렇게 결정했는가 | 관련 영역을 만지기 전 |
| `docs/domain/` | 도메인 개념 정리. Asterisk와 콜센터가 어떻게 동작하는가 | 개념이 낯설 때 |
| `docs/notes/` | 구현 노트. 각 모듈이 어떤 구조이고 왜 그렇게 짰는가 | 해당 모듈을 만지기 전 |
| `docs/troubleshooting/` | 문제 해결 기록. 증상 → 진단 → 원인 → 조치. 번호 순서로 쌓는다 | 비슷한 증상을 만났을 때 |

결정을 바꿀 때는 기존 ADR을 고치지 않고 새 ADR로 대체한다.

지금 있는 문서는 다음과 같다.

| 문서 | 내용 |
|---|---|
| [docs/rules/conventions.md](docs/rules/conventions.md) | 코딩 컨벤션 |
| [docs/adr/0001](docs/adr/0001-ami-over-ari.md) | Asterisk 제어는 AMI, 콜 분배는 dialplan |
| [docs/adr/0002](docs/adr/0002-linkedid-as-call-id.md) | 통화 식별자는 linkedid |
| [docs/adr/0003](docs/adr/0003-dialplan-optin-tracking.md) | 추적할 콜은 dialplan이 알려준다 |
| [docs/adr/0004](docs/adr/0004-call-state-machine.md) | 콜 상태는 4개, 벨울림은 상태가 아니다 |
| [docs/adr/0005](docs/adr/0005-agent-state-model.md) | 상담원 상태는 4개, 후처리는 상태가 아니라 이석 사유다 |
| [docs/adr/0006](docs/adr/0006-spring-events-between-modules.md) | 모듈 사이의 통지는 Spring 이벤트로 한다 |
| [docs/adr/0007](docs/adr/0007-user-extension-mapping.md) | 사용자와 내선을 분리하고 login_id로 식별한다 |
| [docs/adr/0008](docs/adr/0008-stomp-agent-topics.md) | 상태 푸시는 STOMP, 토픽은 상담원별로 나눈다 |
| [docs/adr/0009](docs/adr/0009-outbound-click-to-call.md) | 아웃바운드는 pending 선등록, 발신은 아웃바운드 이석에서만 |
| [docs/adr/0010](docs/adr/0010-call-event-push.md) | 콜 이벤트는 종류별 메시지로 상담원 토픽에 푸시한다 |
| [docs/domain/asterisk-call-model.md](docs/domain/asterisk-call-model.md) | 채널·브리지·context 등 Asterisk가 통화를 보는 방식 |
| [docs/domain/queue-call-events.md](docs/domain/queue-call-events.md) | 큐 콜에서 실제로 오는 AMI 이벤트와 함정. 상태 머신 설계의 입력 |
| [docs/domain/queue-member-events.md](docs/domain/queue-member-events.md) | 큐 멤버 투입/이석 시 오는 AMI 이벤트와 함정. 상담원 상태 구현의 입력 |
| [docs/domain/outbound-call-events.md](docs/domain/outbound-call-events.md) | Originate 발신에서 오는 AMI 이벤트와 함정. 아웃바운드 설계의 입력 |
| [docs/notes/ami.md](docs/notes/ami.md) | AMI 연결 (`ami` 모듈) |
| [docs/notes/call-assembly.md](docs/notes/call-assembly.md) | 콜 조립 (`call` 모듈) |
| [docs/notes/agent.md](docs/notes/agent.md) | 상담원 상태 (`agent` 모듈) |
| [docs/notes/control.md](docs/notes/control.md) | 통화 제어. 클릭투콜 (`control` 모듈) |
| [docs/notes/push.md](docs/notes/push.md) | 상태 푸시와 테스트 페이지 (`push` 모듈) |
| [docs/notes/threading.md](docs/notes/threading.md) | 스레드 모델. 어떤 스레드가 있고 각 스레드에서 뭘 해도 되는가 |
| [docs/troubleshooting/0001](docs/troubleshooting/0001-registered-but-unreachable.md) | 등록은 되어 있는데 벨이 안 간다 — 프록시 매핑 소멸과 qualify |

문서는 [ADR-0001](docs/adr/0001-ami-over-ari.md)의 톤으로 쓴다.
짧은 문장, 핵심 문제는 질문으로, 비교는 표로, 구조는 그림으로.
어려운 용어는 풀어 쓴다.

---

## 현재 상태

Date: 2026-08-19

### 되어 있는 것

| 구분 | 내용 |
|---|---|
| 개발환경 | docker compose (PostgreSQL + Flyway + Asterisk host 네트워크) |
| 내선 | 1000·1001 상담원, 1234 고객 역할. DB(ps_* 테이블)에서 조회 |
| 통화 경로 | 1234 → `0212345678` → queue01 → 상담원. 에코 테스트는 `600` |
| AMI 연결 | 기동 시 로그인, 종료 시 로그아웃. 리스너 자동 수집 (`ami` 모듈) |
| 콜 조립 골격 | UserEvent로 통화 생성, linkedid로 채널 묶기, 마지막 Hangup에서 종료 (`call` 모듈) |
| 콜 상태 머신 | RINGING→QUEUED→CONNECTED→ENDED 전이와 벨울림 기록 ([ADR-0004](docs/adr/0004-call-state-machine.md)) |
| AMI 명령 경로 | 큐 멤버 투입/이석/제거 Action 전송 (`ami` 모듈 `AmiQueueActions`) |
| 상담원 상태 | 로그인·이석·해제·로그아웃 REST. 상태 변경 창구는 `AgentService` 하나 (`agent` 모듈, [ADR-0005](docs/adr/0005-agent-state-model.md)) |
| 사용자-내선 분리 | API 키는 `login_id`, 내선은 DB 사전 매핑, membername에 login_id ([ADR-0007](docs/adr/0007-user-extension-mapping.md)) |
| 상태 푸시 | STOMP `/topic/agents/{loginId}`로 상담원 스냅샷 푸시 (`push` 모듈, [ADR-0008](docs/adr/0008-stomp-agent-topics.md)) |
| 착신 알림 | 콜 이벤트를 봉투 구조(`type`·`event`·`data`)로 상담원 토픽에 푸시 ([ADR-0010](docs/adr/0010-call-event-push.md)) |
| CTI 테스트 페이지 | `/agents.html`. 상담원 조작·상태 실시간 표시·큐 배정 관리·아웃바운드 이석/발신 |
| 콜-상담원 연동 | 콜 연결/종료를 Spring 이벤트로 발행, 상담원 ON_CALL·ACW 전이 ([ADR-0006](docs/adr/0006-spring-events-between-modules.md)) |
| 아웃바운드 (클릭투콜) | POST `/api/v1/calls`. ChannelId 예약, pending 선등록, DialEnd 응답 감지, PAUSED(OUTBOUND) 게이트 ([ADR-0009](docs/adr/0009-outbound-click-to-call.md), `control` 모듈) |
| DB 접근 | `agents`·`agent_queues`에서 상담원과 큐 배정 조회 (JdbcClient) |
| 단위 테스트 | 번역기 17개 + 상담원 세션 13개 + 상담원 서비스 6개 + 발신 게이트 5개 + 콜 푸시 4개 통과 |
| 실통화 검증 | 콜 6개 + 상담원 REST 9개 + 아웃바운드 4개 + 콜 푸시 7개 시나리오 확인 완료 ([푸시 노트](docs/notes/push.md)) |

설계 결정: [ADR-0002](docs/adr/0002-linkedid-as-call-id.md) 통화 식별자는 linkedid,
[ADR-0003](docs/adr/0003-dialplan-optin-tracking.md) 추적할 콜은 dialplan이 UserEvent로 알려준다.

검증 결과와 실측한 이벤트 순서는
[콜 조립 노트](docs/notes/call-assembly.md)의 실통화 검증 결과 절에 있다.

### 로드맵 (의존 순서)

1. ~~사용자-내선 분리~~ — 완료 ([ADR-0007](docs/adr/0007-user-extension-mapping.md))
2. ~~WebSocket 푸시~~ — 완료 ([ADR-0008](docs/adr/0008-stomp-agent-topics.md)).
   실검증(상담원 REST 재검증, ON_CALL·ACW, 푸시 수신)은 테스트 페이지 `/agents.html`에서 한다.
   콜 이벤트 푸시는 착신 알림(4번)에서
3. ~~아웃바운드 (클릭투콜)~~ — 완료 ([ADR-0009](docs/adr/0009-outbound-click-to-call.md),
   [실측](docs/domain/outbound-call-events.md), [노트](docs/notes/control.md))
4. **받기·끊기·착신 알림** — 착신 알림 완료·실통화 검증 완료
   ([ADR-0010](docs/adr/0010-call-event-push.md), [푸시 노트](docs/notes/push.md)).
   받기·끊기가 단말 제어 첫 발. 받기는 단말 지원 실측 스파이크 먼저
5. **보류/해제** — 코드 전에 Asterisk 실측 스파이크 먼저.
   채널별 역할과 bridge 추적(콜 모델 확장)이 선행 조건
6. **호전환 (블라인드 → 협의) → 3자 통화** — 소유권 이전 포함
7. **읽기 모델·상태 이력·통계** — calls/참여 이력 영속화, 상담원 상태 이력 테이블
8. **녹취** — 자동 녹취, 조회, 후처리

통화 제어(5·6)의 완료 판정은 이벤트 도착 순서가 아니라
최종 bridge 구성으로 한다. 실패한 명령은 복구하지 않고 격리한다.

재동기화(재시작 시 세션 복원, 외부 조작 흡수)는 당장 하지 않기로 했다.
알려진 한계는 [상담원 노트](docs/notes/agent.md)의 "아직 없는 것"에 있다.

---

## 자주 쓰는 명령

    docker compose up -d
    ./gradlew bootRun                # AMI login OK 로그 확인
    ./gradlew test

    # Asterisk CLI
    docker compose exec asterisk asterisk -rvvv
    docker compose exec asterisk asterisk -rx "pjsip show contacts"
    docker compose exec asterisk asterisk -rx "queue show queue01"
    docker compose exec asterisk asterisk -rx "dialplan reload"

    # 상담원 로그인/이석 (큐 투입은 CLI 대신 이 API를 쓴다)
    # 키는 login_id. 개발 시드: agent1→내선 1000, agent2→내선 1001
    curl -X POST localhost:3000/api/v1/agents/agent1/login
    curl -X POST localhost:3000/api/v1/agents/agent1/unpause
    curl -X POST localhost:3000/api/v1/agents/agent1/pause -H 'Content-Type: application/json' -d '{"reason":"lunch"}'
    curl -X POST localhost:3000/api/v1/agents/agent1/logout
    curl localhost:3000/api/v1/agents

소프트폰 서버 주소는 개발 PC와 같은 곳이면 `127.0.0.1:5060` (UDP).
계정은 내선번호가 아이디. 비밀번호는 상담원 내선(1000·1001)은 내선번호와 동일,
고객 역할(1234)은 `1234pass`.
