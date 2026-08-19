# ADR-0010: 콜 이벤트는 종류별 메시지로 상담원 토픽에 푸시한다

Status: Accepted
Date: 2026-08-19

## Context

착신 알림을 만든다. 상담원 화면에 "전화 왔다" 팝업을 띄우고,
벨이 멈추면 내리는 기능이다.

재료는 이미 있다.

- 벨울림 기록: `AgentCalled`가 울리는 상담원을 통화에 기록하고,
  `AgentRingNoAnswer`가 지운다 ([ADR-0004](0004-call-state-machine.md)).
  ADR-0004는 이 기록의 변화를 push하는 것까지 예고해 두었다.
- 푸시 채널: STOMP `/topic/agents/{loginId}`.
  착신 팝업까지 같은 주소를 쓰기로 했다 ([ADR-0008](0008-stomp-agent-topics.md)).

남은 질문은 이것이다.

> 팝업을 내리는 신호를 어떻게 보낼 것인가?

벨이 멈추는 경로는 세 가지다. 세 경우 모두 팝업은 내려가야 한다.

| 경로 | 무슨 일 | 벨울림 기록은 |
|---|---|---|
| 무응답 | 안 받아서 다음 상담원에게 넘어감 (`AgentRingNoAnswer`) | 지워진다 |
| 응답 | 상담원이 받음 (`AgentConnect`) | 지워지고 상담원 확정 |
| 포기호 | 벨 울리는 도중 고객이 끊음 (마지막 `Hangup`) | 통화와 함께 사라진다 |

## Decision

### 메시지는 사건별로 나눈다

"팝업 내려라" 하나로 합치지 않고, 일어난 일을 그대로 보낸다.

| event | 근거 | 받는 사람 | data |
|---|---|---|---|
| `RINGING` | 벨울림 기록 생성 (`AgentCalled`) | 울리는 상담원 | callId, customerNumber, queueName |
| `RINGING_CANCELED` | 벨울림 기록 삭제 — 무응답, 또는 벨 중 통화 종료 | 울리던 상담원 | callId |
| `DIALING` | 아웃바운드에서 고객 호출 시작 (`DialBegin`, 응답 전만) | 발신 상담원 | callId, customerNumber |
| `ANSWERED` | 응답 (`AgentConnect`, 아웃바운드는 `DialEnd`) | 받은 상담원 | callId, direction |
| `ENDED` | 상담원이 확정된 통화의 종료 | 그 상담원 | callId, direction |

`RINGING`과 `DIALING`은 방향이 반대라 나눈다.
`RINGING`은 받는 상담원에게 "팝업 띄워라", `DIALING`은 건 상담원에게 "고객 호출 중".

`DialBegin`은 큐가 상담원을 부를 때도, 나중에 전환이 협의 다이얼을 할 때도 온다.
그래서 `DIALING`은 아웃바운드이면서 응답 전(RINGING 상태)일 때만 만든다.
소스 채널이 없는 `DialBegin`(Originate 단계)도 걸러진다.

클라이언트 규칙: `RINGING`이면 팝업을 띄우고,
`RINGING_CANCELED`나 `ANSWERED`가 오면 내린다.

두 안을 비교했다.

| | 내림 신호를 하나로 합침 | 사건별 메시지 |
|---|---|---|
| 메시지 종류 | 2개 (RINGING / CANCELED) | 4개 |
| 클라이언트 규칙 | 하나 | 팝업 규칙은 둘, ENDED는 별도 용도 |
| 다음 단계 재료 | 없음 | ANSWERED가 스크린팝, ENDED가 통화 종료 표시의 재료 |

받기·끊기(로드맵 4의 나머지)와 화면 연동이 바로 뒤에 있다.
그때 어차피 필요한 메시지를 지금 나눠 둔다.

### 토픽은 재사용, 봉투 구조로 구분한다

ADR-0008의 `/topic/agents/{loginId}`에 그대로 보낸다.
같은 토픽에 상담원 스냅샷과 콜 이벤트가 섞이므로
모든 푸시 메시지를 봉투 구조로 통일한다.

    {"type":"AGENT", "data":{"loginId":"..","status":"PAUSED", ...}}
    {"type":"CALL", "event":"RINGING", "data":{"callId":"..","customerNumber":"..","queueName":".."}}

- `type`은 계열이다. `AGENT`는 상태 스냅샷, `CALL`은 콜 이벤트.
- 세부 필드는 계열마다 다르다. `AGENT`는 스냅샷 한 종류라 세부가 없고
  (상태는 `data.status`), `CALL`은 `event`가 어떤 사건인지 알려준다.
  상태(지금 이렇다)와 사건(일어난 일)은 다른 것이라 필드도 나눈다.
- 내용은 전부 `data` 안에 둔다. 나중에 공통 필드(commandId 등)가 생기면
  봉투에 자리가 있다.
- 값이 없는 필드는 직렬화하지 않는다.

기존에 raw record로 나가던 발신 실패 푸시도
같은 봉투(`event: OUTBOUND_FAILED`, data에 reason 코드)로 편입한다.

### 발행 경로와 책임

    번역기 ──> Spring 이벤트 (call 모듈) ──> CallEventPushListener (push 모듈) ──> STOMP

- `call` 모듈은 Spring 이벤트 2개를 새로 발행한다:
  `CallRingingEvent`, `CallRingingCanceledEvent`.
  응답과 종료는 기존 `CallConnectedEvent`, `CallEndedEvent`를 재사용한다.
- 이벤트에는 상담원이 `interface`(`PJSIP/1000`)로 실려 있다.
  loginId 해석은 `push` 모듈이 `AgentSessionRegistry.findByInterface`로 한다.
  `call` 모듈은 상담원 개념을 모른 채로 남는다.
- 세션이 없으면(미로그인) 드롭하고 warn 로그를 남긴다.
  팝업을 받을 화면이 없는 상담원이다.

### 포기호는 벨울림 취소로 알린다

포기호에서는 상담원이 확정된 적이 없어서 `CallEndedEvent`에 상담원이 없다.
보낼 곳이 없으므로 `ENDED`는 나가지 않는다.

대신 마지막 `Hangup` 시점에 벨울림 기록이 남아 있으면
`CallRingingCanceledEvent`를 발행한다. 울리던 상담원의 팝업이 이걸로 내려간다.
"벨울림 기록이 지워졌다"는 사실 그대로다.

### 지금 하지 않는 것

- **울리는 채널(destUniqueId) 저장.** 받기(talk 계열 자동응답)의 대상 채널이라
  받기를 설계할 때 벨울림 기록에 추가한다. 착신 알림에는 필요 없다.
- **메시지 순번·버전.** 놓친 메시지의 복구는 재동기화의 일이다.

## Why

### 왜 스냅샷이 아니라 이벤트인가

상담원 상태는 스냅샷으로 민다 (ADR-0008). "지금 이렇다"를 그리는 화면이라
마지막 메시지 하나로 충분하기 때문이다.

착신 팝업은 다르다. "일어난 일"에 반응하는 UI다.
울리기 시작했다 → 띄운다, 멈췄다 → 내린다.
사실이 일어난 순간을 전달하는 게 목적이므로 이벤트가 맞다.

### 왜 push 모듈이 loginId를 해석하는가

`interface`와 loginId의 매핑은 상담원 세션이 안다 (ADR-0007).
`call` 모듈에 해석을 시키면 `call`이 `agent`를 알게 된다.
지금 방향은 반대다: `agent`가 `call`의 이벤트를 구독한다 (ADR-0006).
해석을 push 모듈에 두면 이 방향이 유지된다.

## Consequences

- 아웃바운드도 `ANSWERED`·`ENDED`를 받는다. 팝업은 없지만
  화면이 통화 시작·종료를 아는 재료가 된다.
- 큐를 거치지 않는 착신에는 팝업이 없다. `AgentCalled`가 오지 않기 때문이다.
  지금 추적하는 통화는 큐 인바운드와 클릭투콜뿐이라 문제가 안 된다.
- 무응답 재분배에서는 팝업이 상담원을 옮겨 다닌다.
  RINGING(상담원1) → CANCELED(상담원1) → RINGING(상담원2) → …
- 테스트 페이지가 봉투의 `type`으로 메시지를 구분하게 바뀐다.
- 구독 인증이 없는 것은 그대로다 (ADR-0008). 착신 팝업의 고객 번호도
  아무나 구독해 볼 수 있다. 인증 체계가 생길 때 같이 막는다.

## Revisit when

- 받기를 만들 때. 울리는 채널 저장이 필요해진다.
- 화면 연동(스크린팝)이 생길 때. `ANSWERED`의 data에
  고객 번호 등이 추가로 필요할 수 있다.
- 감독 화면이 생길 때. 콜 이벤트의 브로드캐스트 토픽을 검토한다.
