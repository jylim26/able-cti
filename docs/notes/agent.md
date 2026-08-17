# 상담원 상태

Date: 2026-08-16

## Goal

`agent` 모듈은 상담원 세션과 상태를 소유한다.

로그인(큐 투입), 이석/해제, 로그아웃(큐 제거)을 REST로 받아 AMI 명령으로 바꾼다.
상태를 바꾸는 창구는 `AgentService` 하나다. CLI 등 외부 조작은
MVP에서 지원하지 않는다 (아래 "외부 조작은 MVP에서 다루지 않는다").

상태 모델은 [ADR-0005](../adr/0005-agent-state-model.md)에,
실측한 이벤트는 [큐 멤버 조작에서 오는 AMI 이벤트](../domain/queue-member-events.md)에 있다.

---

## Structure

    REST (AgentController)
         ↓
    AgentService              명령 흐름: AMI 전송 → 세션 반영
         ↓                ↘
    AgentSessionRegistry      AmiQueueActions (ami 모듈)
         ↓                    ← 처음 생긴 명령 전송 경로
    AgentSession              상태 머신. asterisk-java를 모른다

콜 조립과 같은 경계 규칙이다. asterisk-java 타입은
`AmiQueueActions`까지만 오고, 도메인(`AgentSession`)은 업무의 말만 쓴다.

`AmiQueueActions`는 `ami` 모듈에 처음 생긴 "보내는 쪽"이다.
`sendAction`(타임아웃 5초)의 응답이 `ManagerError`면 `AmiActionException`을
던지고, REST 끝에서 502로 나간다. 큐 멤버 명령은 접수 즉시 완료되는
종류라 응답 확인으로 충분하다. 결과를 기다려야 하는 명령(전환 등)이
생기면 그때 완료 판정을 따로 설계한다.

---

## 명령 흐름

### 로그인은 명령 두 개다

    QueueAdd (Paused: true)  →  QueuePause (Reason: LOGIN)

`QueueAdd`에는 사유 헤더가 없다 (실측). 이석 상태로 먼저 투입하므로
두 명령 사이에 콜이 인입될 틈은 없다.

큐 배정은 DB(`agents`, `agent_queues`)가 원천이다.
클라이언트는 내선만 보내고, 어느 큐에 들어갈지 고르지 않는다.

### AMI 전송이 먼저, 세션 반영이 나중

어긋남의 방향을 통제하기 위해서다.

| 순서 | 실패하면 | 결과 |
|---|---|---|
| 세션 먼저 바꾸고 AMI 전송 | 세션만 이석, Asterisk는 READY | 이석인 줄 알았는데 콜이 꽂힌다 |
| AMI 먼저 보내고 세션 반영 | Asterisk만 바뀌고 세션은 그대로 | 조회가 잠시 틀릴 뿐, 콜이 잘못 꽂히지는 않는다 |

어긋나더라도 안전한 방향(콜이 안 가는 쪽)으로 어긋나게 한다.
로그인만 예외로 세션을 먼저 등록하고, 명령이 실패하면 지운다.

### 외부 조작은 MVP에서 다루지 않는다

CLI 등으로 큐 멤버를 직접 조작하면 세션과 Asterisk가 어긋난다.
이를 이벤트로 흡수하는 리스너는 만들지 않는다.
MVP에서 외부 조작은 지원하지 않는 조작이고,
지원하지 않는 것을 방어하는 코드이기 때문이다.

재시작 복구, 관리자 강제 이석, 외부 CTI 연동이 생길 때
"외부 상태 재동기화" 요구와 함께 넣는다.
그때 쓸 실측 기록은 [큐 멤버 이벤트](../domain/queue-member-events.md)에 있다.

### 통화 중 이석/해제는 복귀 목적지를 바꾼다

ON_CALL에서 이석/해제는 상태를 바꾸지 않는다.
통화가 끝나면 어디로 갈지(`pauseAfterCallReason`)만 바꾼다.
ADR-0005의 "통화 전 상태로 복귀"가 이 기록을 쓴다.

콜이 연결되면 그때의 이석 사유가 복귀 목적지로 옮겨지고,
통화 중 `pauseReason`은 비어 있다. 이석 사유는 PAUSED의 것이지
ON_CALL의 것이 아니다.

---

## 규율

1. **리스너에서 `sendAction`을 부르지 않는다.**
   이벤트를 전달하는 스레드가 명령 응답까지 읽는 구조라서,
   리스너 안에서 응답을 기다리면 자기 자신을 기다리게 된다.
   이벤트를 받아 명령을 보내야 하는 날이 오면 별도 스레드로 넘긴다.
2. **세션 메서드는 `synchronized`다.**
   지금 세션을 건드리는 스레드는 REST 요청뿐이지만,
   콜 연동이 붙으면 이벤트 펌프가 다시 들어온다.

### setter 충돌은 라이브러리가 로그로 알려준다

기동 후 첫 `QueueMemberPause`에서 asterisk-java가 이런 로그를 남긴다.

    multiple setters (case insensitive) exist for pausedreason ...
    Preferring setter from extending class ... setPausedreason(String)

`PausedReason` 헤더의 도착지가 `getPausedreason()`(소문자 r)이라는
[실측](../domain/queue-member-events.md)과 일치한다. 이 로그가 사라지거나
방향이 바뀌면 라이브러리 버전업으로 계약이 바뀐 것이다.

---

## 아직 없는 것

- **ON_CALL·ACW 전이의 배선.** `AgentSession`에는 `callConnected`와
  `normalCallEnded`/`queueInboundCallEnded`가 있고 테스트도 통과하지만,
  콜 이벤트와 아직 잇지 않았다. 다음 단계다.
- **멀티 큐의 이석 단위.** 이석은 상담원 단위라 모든 큐에 일괄 적용된다.
  큐별로 따로 이석하는 요구가 생기면 세션 모델을 다시 본다.
- **외부 조작 재동기화.** CLI로 이석/해제/제거를 하면 세션과 Asterisk가
  어긋난 채로 남는다. 당장 계획은 없고, 필요해지면 그때 다룬다
  (위 "외부 조작은 MVP에서 다루지 않는다").
- **재시작 복원.** CTI가 재시작하면 세션이 증발하는데 Asterisk에는
  멤버가 남아 있다. 그 상태에서 재로그인하면 `QueueAdd`가
  "Already there"로 실패한다(502). 당장 계획은 없다.

---

## Verification

1. `./gradlew test` — 세션 전이 테스트 13개.
   ADR-0005의 표를 한 줄씩 옮긴 것이다.
2. 실검증. 앱을 띄우고 REST로 조작하며 큐 상태를 대조한다.

### 실검증 결과

Date: 2026-08-16

| 시나리오 | 기대 | 결과 |
|---|---|---|
| 로그인 | PAUSED(LOGIN), 큐에 `paused:LOGIN` 멤버 생성 | 통과 |
| 이석 해제 | READY, 큐에서 unpause | 통과 |
| 사유 있는 이석 | PAUSED(lunch), 큐에 `paused:lunch` | 통과 |
| 로그아웃 | 204, 큐 멤버 제거, 세션 소멸 | 통과 |
| 중복 로그인 | 409 | 통과 |
| 미로그인 상담원 조작 | 404 | 통과 |
| 예약 사유(ACW)로 이석 요청 | 400 | 통과 |
| 없는 내선 로그인 | 404 | 통과 |

    agent login:    extension=1000 queues=[queue01] state=PAUSED reason=LOGIN
    agent unpaused: extension=1000 state=READY
    agent paused:   extension=1000 reason=lunch state=PAUSED
    agent logout:   extension=1000 state=LOGGED_OUT
