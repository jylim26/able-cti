# 큐 멤버 조작에서 오는 AMI 이벤트

Date: 2026-08-16

상담원 상태([ADR-0005](../adr/0005-agent-state-model.md)) 구현의 입력이다.
AMI에 직접 접속(TCP 5038)해 큐 멤버 명령을 보내고,
응답과 이벤트를 그대로 기록했다. 추측이 아니라 로그에서 확인한 것만 적는다.

관찰 환경은 queue01, 내선 1000이다.

---

## 1. 명령과 그 결과

명령마다 응답이 오고, 성공하면 이벤트가 뒤따른다.

| 명령 (Action) | 응답 Message | 뒤따르는 이벤트 |
|---|---|---|
| `QueueAdd` | `Added interface to queue` | `QueueMemberAdded` |
| `QueuePause` (paused=true) | `Interface paused successfully` | `QueueMemberPause` |
| `QueuePause` (paused=false) | `Interface unpaused successfully` | `QueueMemberPause` |
| `QueueRemove` | `Removed interface from queue` | `QueueMemberRemoved` |

세 이벤트 모두 같은 필드 묶음을 싣는다. 관찰값 기준으로 중요한 것만 적는다.

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `Queue` | `queue01` | 큐 이름 |
| `Interface` | `PJSIP/1000` | 큐 멤버 식별자. **상담원을 가리키는 값** |
| `Paused` | `1` / `0` | 이석 여부 |
| `PausedReason` | `LOGIN`, `lunch`, 빈 값 | 이석 사유 |
| `Membership` | `dynamic` | 동적으로 투입된 멤버 |
| `LoginTime` | epoch 초 | 투입된 시각 |
| `LastPause` | epoch 초 | 마지막 이석 시각 |
| `Status` | `1` / `5` | 단말의 디바이스 상태. 1=사용 가능, 5=미등록(관찰값) |

---

## 2. QueueAdd에는 사유를 실을 수 없다

`QueueAdd`에 `Paused: true`를 넣으면 이석 상태로 투입되지만,
사유를 지정하는 헤더가 없다. `QueueMemberAdded`는 이렇게 왔다.

    Paused: 1
    PausedReason:          ← 빈 값

asterisk-java의 `QueueAddAction`에도 reason setter가 없다.

> 로그인을 "이석 상태로 투입 + 사유는 LOGIN"으로 만들려면
> `QueueAdd(paused=true)` 뒤에 `QueuePause(reason=LOGIN)`를 한 번 더 보낸다.
> 이미 이석 상태라 그 사이에 콜이 인입될 틈은 없다.

---

## 3. 해제하면 사유도 지워진다

`QueuePause(paused=false)` 뒤의 이벤트다.

    Paused: 0
    PausedReason:          ← 빈 값

CLI로 해제해도 같다. 사유는 이석에 붙은 값이라 해제와 함께 사라진다.
"이석 해제 = 사유 소멸"이 Asterisk의 사실이므로 세션도 같은 규칙을 따르면 된다.

---

## 4. 자기 명령의 에코도 온다

CTI가 보낸 `QueuePause`에도 `QueueMemberPause`가 온다.
CLI에서 조작한 것과 이벤트 형태가 완전히 같아서 **구별할 수 없다.**

    (CTI가 QueuePause 전송)           (CLI에서 queue pause member)
    Event: QueueMemberPause           Event: QueueMemberPause
    Interface: PJSIP/1000             Interface: PJSIP/1000
    Paused: 1                         Paused: 1
    PausedReason: lunch               PausedReason: cli-test

이벤트를 그대로 세션에 반영하면 자기 명령이 두 번 적용된다.

> 반영은 멱등으로 한다. 세션이 이미 그 상태면 아무 일도 하지 않는다.
> 에코는 이미 적용된 상태와 같으므로 자연히 걸러지고,
> CTI 밖에서 일어난 조작만 세션을 바꾼다.

---

## 5. QueueStatus — 전체 큐 멤버 조회

응답이 여러 이벤트로 나뉘어 오는 EventList 형식이다.

    Response: Success
    EventList: start
      ↓
    QueueParams          큐 자체의 통계 (strategy, holdtime, ...)
    QueueMember          멤버마다 하나. Paused, PausedReason, Membership 포함
    ...
    QueueStatusComplete  EventList: Complete, ListItems: n

재동기화(CTI 재시작 후 세션 복원)가 이 액션을 쓰게 된다.
`Membership: dynamic`으로 CTI가 투입한 멤버를 구별할 수 있다.

---

## 6. 함정

### 채워지는 getter가 따로 있다

asterisk-java의 `QueueMemberPauseEvent`에는 사유 getter가 **둘** 있다.

| getter | 선언 위치 | `PausedReason` 헤더가 채우는가 |
|---|---|---|
| `getPausedReason()` | 부모 (AbstractQueueMemberEvent) | **아니다. 항상 null** |
| `getPausedreason()` | 자식 (QueueMemberPauseEvent) | 채워진다 |

asterisk-java의 실제 파싱 경로에 `PausedReason: LOGIN` 헤더를 태워 확인했다.
`getPausedreason()`만 값이 나온다. IDE 자동완성은 대문자 R쪽을 먼저 보여주므로
컴파일이 되고 조용히 null이 들어온다.
[콜 조립 노트](../notes/call-assembly.md)의 "이름이 곧 헤더와의 계약" 함정의 변형이다.

### DeviceStateChange 노이즈

이석/해제마다 큐가 내부적으로 쓰는 디바이스 상태가 따라온다.

    Device: Queue:queue01_pause_PJSIP/1000
    Device: Queue:queue01_avail

큐 콜에서와 마찬가지로 구독하지 않는다.

### Paused는 문자열 "1"/"0"으로 온다

asterisk-java가 Boolean으로 바꿔 준다. `getPaused()`는 정상 동작을 확인했다.
