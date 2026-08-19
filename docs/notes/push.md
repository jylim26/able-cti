# 상태 푸시

Date: 2026-08-19

## Goal

`push` 모듈은 서버에서 클라이언트로 밀어주는 채널을 소유한다.
미는 것은 상담원 상태 스냅샷과 콜 이벤트 두 가지다.
결정은 [ADR-0008](../adr/0008-stomp-agent-topics.md)과
[ADR-0010](../adr/0010-call-event-push.md)에 있다.

---

## Structure

    AgentService ──발행──> AgentStateChangedEvent (agent 모듈)
                               │ Spring 이벤트 (ADR-0006)
                               ▼
                     AgentStatePushListener (push 모듈)
                               │ convertAndSend
                               ▼
                     /topic/agents/{loginId}  ← STOMP simple broker
                               ▲
                               │ convertAndSend
                     CallEventPushListener (push 모듈)
                               │ interface → loginId 해석 (AgentSessionRegistry)
                               │ 세션 없으면 드롭 + warn
    번역기 ──발행──> CallRinging·CallRingingCanceled·CallConnected·CallEnded (call 모듈)

- WebSocket 엔드포인트는 `/ws`, 토픽 접두어는 `/topic` (`WebSocketConfig`).
- 모든 메시지는 봉투 구조다 (ADR-0010):
  `{"type":"AGENT","data":{...}}` / `{"type":"CALL","event":"RINGING","data":{...}}`.
  wire 포맷은 push 모듈 소유라 메시지 record(`AgentStateMessage`, `CallEventMessage`)로
  변환해 보낸다. Spring 이벤트 record를 그대로 내보내지 않는다.
- `AgentService`는 상태를 바꾼 모든 자리에서 `publishState`를 부른다.
  data는 그 상담원의 현재 스냅샷이다 (델타 아님).
- 콜 이벤트는 `RINGING` / `RINGING_CANCELED` / `DIALING` / `ANSWERED` / `ENDED` / `OUTBOUND_FAILED`.
  상담원이 확정되지 않은 종료(포기호)는 보낼 곳이 없어 푸시하지 않는다.
- `agent` 모듈은 WebSocket을 모르고, `push` 모듈은 상태를 만들지 않는다.

## 스레드

`convertAndSend`는 브로커 채널에 메시지를 넘길 뿐 응답을 기다리지 않는다.
실제 소켓 전송은 Spring의 clientOutboundChannel 스레드가 한다.
그래서 AMI 리더 스레드에서 발행해도 된다 —
[스레드 모델](threading.md)의 `sendAction` 금지와는 다른 종류다.

## 테스트 페이지 (CTI 테스트)

`static/agents.html` → `http://localhost:3000/agents.html`

CTI 기능 전반의 개발용 조작판이다. 지금 있는 것:

- loginId를 추가하면 그 토픽을 구독하고 조작 버튼(로그인/해제/이석/로그아웃)이 생긴다.
- 착신 표시: `RINGING`이 오면 콜 셀에 발신 번호와 큐를 빨갛게 띄우고,
  다른 콜 이벤트가 오면 지운다. 통화 중 표시는 상담원 스냅샷이 그린다.
- 큐 배정 셀: 전체 큐 체크박스에 현재 배정이 체크되어 있고,
  저장하면 `PUT /agents/{loginId}/queues`로 교체한다. 다음 로그인부터 적용.
- 로그: 푸시(파랑)/성공(초록)/실패(빨강) 구분, 비우기 버튼, raw JSON은 툴팁.
- 페이지 로드 시 REST(`GET /api/v1/queues`, `GET /api/v1/agents`)로 초기화한다.
  이후는 푸시가 덮어쓴다. simple broker에 와일드카드 구독이 없어서
  상담원별로 구독한다 (ADR-0008).
- STOMP 클라이언트는 CDN(@stomp/stompjs)이라 오프라인에서는 안 뜬다. 개발 도구라 수용.

## 실통화 검증 결과 (2026-08-19)

테스트 페이지에서 소프트폰 2대(1000 상담원, 1234 고객)로 확인했다.

| 시나리오 | 결과 |
|---|---|
| 인바운드 정상 | RINGING → ANSWERED → ENDED. 착신 표시가 뜨고 지워짐 |
| 상담원 무응답 | 벨 타임아웃마다 RINGING_CANCELED, 큐 재시도마다 RINGING 다시 옴 |
| 고객 포기 (벨 중 끊음) | RINGING_CANCELED만 오고 ENDED는 안 옴 (상담원 미확정 종료는 드롭) |
| 두 상담원 이관 | RINGING·RINGING_CANCELED가 벨 울리는 상담원 토픽에만 감 |
| 아웃바운드 정상 | DIALING → ANSWERED → ENDED |
| 아웃바운드 실패 | 내선 미등록이면 OUTBOUND_FAILED |
| 인바운드 회귀 | 인바운드에서 DIALING 안 나감 (DialBegin 가드 동작) |

실측에서 안 것:

- **상담원 무응답 아웃바운드는 OUTBOUND_FAILED가 아니라 ENDED(answered=false)다.**
  내선이 등록돼 있으면 채널이 생겨서 콜로 등록되고, 무응답 종료는 Hangup 경로를 탄다.
  OUTBOUND_FAILED는 채널 자체가 안 생긴 경우(미등록 등)에만 온다.
- **OUTBOUND_FAILED의 reason은 Asterisk 숫자 코드다** (미등록이면 `0`).
  사람이 읽을 문구가 필요해지면 그때 매핑한다.

## 아직 없는 것

- **구독 인증 없음.** 누구나 아무 토픽이나 구독할 수 있다.
  착신 푸시의 고객 번호도 그대로 노출된다.
- **전체 뷰 토픽 없음.** 감독 화면이 생기면 브로드캐스트 토픽을 추가한다.
