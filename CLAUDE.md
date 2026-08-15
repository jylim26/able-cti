# CLAUDE.md

Asterisk 기반 콜센터 CTI 서버. 설계를 하나씩 결정하고 ADR로 남기면서 만든다.

작업을 시작하기 전에 이 문서의 "현재 상태"를 먼저 읽는다.
코드나 설계를 바꿨으면 끝나기 전에 이 문서를 갱신한다.

---

## 문서

docs 하위 폴더는 성격으로 나뉜다. 새 문서는 성격에 맞는 폴더에 넣는다.

| 폴더 | 성격 | 언제 읽는가 |
|---|---|---|
| `docs/rules/` | 지켜야 할 규칙 (코딩 컨벤션 등) | 코드 작성 전 |
| `docs/adr/` | 설계 결정 기록. 무엇을, 왜 그렇게 결정했는가 | 관련 영역을 만지기 전 |
| `docs/domain/` | 도메인 개념 정리. Asterisk와 콜센터가 어떻게 동작하는가 | 개념이 낯설 때 |
| `docs/notes/` | 구현 노트. 각 모듈이 어떤 구조이고 왜 그렇게 짰는가 | 해당 모듈을 만지기 전 |

결정을 바꿀 때는 기존 ADR을 고치지 않고 새 ADR로 대체한다.

지금 있는 문서는 다음과 같다.

| 문서 | 내용 |
|---|---|
| [docs/rules/conventions.md](docs/rules/conventions.md) | 코딩 컨벤션 |
| [docs/adr/0001](docs/adr/0001-ami-over-ari.md) | Asterisk 제어는 AMI, 콜 분배는 dialplan |
| [docs/adr/0002](docs/adr/0002-linkedid-as-call-id.md) | 통화 식별자는 linkedid |
| [docs/adr/0003](docs/adr/0003-dialplan-optin-tracking.md) | 추적할 콜은 dialplan이 알려준다 |
| [docs/domain/asterisk-call-model.md](docs/domain/asterisk-call-model.md) | 채널·브리지·context 등 Asterisk가 통화를 보는 방식 |
| [docs/domain/queue-call-events.md](docs/domain/queue-call-events.md) | 큐 콜에서 실제로 오는 AMI 이벤트와 함정. 상태 머신 설계의 입력 |
| [docs/notes/ami.md](docs/notes/ami.md) | AMI 연결 (`ami` 모듈) |
| [docs/notes/call-assembly.md](docs/notes/call-assembly.md) | 콜 조립 (`call` 모듈) |

문서는 [ADR-0001](docs/adr/0001-ami-over-ari.md)의 톤으로 쓴다.
짧은 문장, 핵심 문제는 질문으로, 비교는 표로, 구조는 그림으로.
어려운 용어는 풀어 쓴다.

---

## 현재 상태

Date: 2026-08-15

### 되어 있는 것

| 구분 | 내용 |
|---|---|
| 개발환경 | docker compose (PostgreSQL + Flyway + Asterisk host 네트워크) |
| 내선 | 1000·1001 상담원, 1234 고객 역할. DB(ps_* 테이블)에서 조회 |
| 통화 경로 | 1234 → `0212345678` → queue01 → 상담원. 에코 테스트는 `600` |
| AMI 연결 | 기동 시 로그인, 종료 시 로그아웃. 리스너 자동 수집 (`ami` 모듈) |
| 콜 조립 골격 | UserEvent로 통화 생성, linkedid로 채널 묶기, 마지막 Hangup에서 종료 (`call` 모듈) |
| 단위 테스트 | 번역기 4개 통과 (`AmiCallEventTranslatorTest`) |
| 실통화 검증 | 정상 통화·포기호·무응답 재분배·추적 제외까지 6개 시나리오 확인 완료 |

설계 결정: [ADR-0002](docs/adr/0002-linkedid-as-call-id.md) 통화 식별자는 linkedid,
[ADR-0003](docs/adr/0003-dialplan-optin-tracking.md) 추적할 콜은 dialplan이 UserEvent로 알려준다.

검증 결과와 실측한 이벤트 순서는
[콜 조립 노트](docs/notes/call-assembly.md)의 실통화 검증 결과 절에 있다.

### 다음 설계 후보

- 콜 상태 머신 — RINGING/QUEUED/CONNECTED 같은 상태와 전이 규칙
- 상담원 상태 — 큐 멤버 투입/이석, 상태 조회의 원천
- 읽기 모델과 전달 — WebSocket/DB로 무엇을 어떤 형태로 내보낼지
- 아웃바운드 — Originate 전에 callId를 돌려주는 방법 (ADR-0002에 후보만 적어둠)

---

## 자주 쓰는 명령

    docker compose up -d
    ./gradlew bootRun                # AMI login OK 로그 확인
    ./gradlew test

    # Asterisk CLI
    docker compose exec asterisk asterisk -rvvv
    docker compose exec asterisk asterisk -rx "pjsip show contacts"
    docker compose exec asterisk asterisk -rx "queue show queue01"
    docker compose exec asterisk asterisk -rx "queue add member PJSIP/1000 to queue01"
    docker compose exec asterisk asterisk -rx "dialplan reload"

소프트폰 서버 주소는 개발 PC와 같은 곳이면 `127.0.0.1:5060` (UDP).
계정은 내선번호가 아이디, 비밀번호는 `내선번호pass` (예: 1000 / 1000pass).
