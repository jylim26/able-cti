# 큐 콜에서 오는 AMI 이벤트

Date: 2026-08-15

큐 인바운드 콜을 실제로 걸어보며 관찰한 이벤트다.
추측이 아니라 로그에서 확인한 것만 적는다.

상태 머신을 만들 때 무엇을 근거로 삼을지 여기서 고른다.

관찰 환경은 내선 1000·1001 상담원, 1234 고객, 큐 timeout 15초다.

---

## 1. 정상 통화

    Newchannel        고객 채널              ← 버린다 (아직 추적 대상인지 모른다)
    UserEvent         CtiCallStarted         ← 통화 생성
    QueueCallerJoin   position=1
    Newchannel        상담원 채널
    AgentCalled       interface=PJSIP/1000   ← 상담원이 울리기 시작
    DialBegin
    Newstate          Ringing
    ── 상담원이 받음 ──
    DialEnd           dialStatus=ANSWER
    QueueCallerLeave
    AgentConnect      holdtime=44 ringtime=10
    BridgeCreate
    BridgeEnter       상담원 채널
    BridgeEnter       고객 채널
    ── 끊음 ──
    BridgeLeave × 2
    BridgeDestroy
    AgentComplete     reason=caller talktime=3
    Hangup            상담원 채널  cause=16 Normal Clearing
    Hangup            고객 채널

---

## 2. 포기호 — 상담원이 받기 전에 고객이 끊음

    ... AgentCalled까지 동일 ...
    DialEnd              dialStatus=CANCEL
    QueueCallerAbandon   holdtime=3 originalposition=1
    QueueCallerLeave
    Hangup × 2

`QueueCallerAbandon`이 포기호를 판정할 근거다.
`AgentConnect`와 `Bridge*`는 오지 않는다. 통화가 연결된 적이 없기 때문이다.

---

## 3. 무응답 재분배

한 통화에서 채널이 네 개까지 생겼다.

    Newchannel   PJSIP/1000-0000001b   상담원1 호출
    AgentRingNoAnswer   ringtime=15000  ← 15초 무응답
    Hangup       PJSIP/1000-0000001b    상담원1 채널 종료

    Newchannel   PJSIP/1001-0000001c   상담원2 호출
    AgentRingNoAnswer   ringtime=15000
    Hangup       PJSIP/1001-0000001c    상담원2 채널 종료

    Newchannel   PJSIP/1000-0000001d   상담원1 재호출
    AgentConnect                        응답
    Hangup × 2                          통화 종료

고객 채널은 이 동안 계속 살아 있다.

### 재호출되면 채널이 새로 생긴다

같은 상담원 1000인데 채널과 uniqueid가 다르다.

| 회차 | 채널 | uniqueid |
|---|---|---|
| 처음 | `PJSIP/1000-0000001b` | `dev-1786776113.27` |
| 재호출 | `PJSIP/1000-0000001d` | `dev-1786776147.29` |

> 상담원을 채널로 식별하면 안 된다. `interface`(`PJSIP/1000`)로 본다.

---

## 4. 근거로 쓸 이벤트

### QueueCallerJoin — 큐 대기 시작

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `position` | `1` | 들어온 시점의 대기 순번 |
| `count` | `1` | 이 큐에서 대기 중인 총 인원 |
| `queue` | `queue01` | 큐 이름 |

`position`은 들어온 순간의 값이다. 앞사람이 빠져서 순번이 당겨질 때
Asterisk가 알려주는지는 확인하지 않았다.

확인하지 않은 이유는 어느 쪽이든 하는 일이 같기 때문이다.
`QueueCallerJoin`과 `QueueCallerLeave`가 모두 오므로 CTI가 큐별 대기 목록을
직접 유지하면 각 통화의 현재 순번은 계산으로 나온다.
이벤트를 기다리지 않는 쪽이 더 확실하다.

### AgentCalled — 상담원이 울리기 시작

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `interface` | `PJSIP/1000` | 큐 멤버 식별자. **상담원을 가리키는 값** |
| `membername` | `PJSIP/1000` | 멤버 이름. 큐 설정에 따라 사람 이름일 수 있다 |
| `destchannel` | `PJSIP/1000-00000019` | 울리는 상담원 채널 |
| `destuniqueid` | `dev-1786776040.25` | 그 채널의 uniqueid |
| `destconnectedlinenum` | `01012345678` | 상담원 단말에 표시될 고객 번호 |

### AgentRingNoAnswer — 상담원이 받지 않음

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `ringtime` | `15000` | 울린 시간. **밀리초**. 큐 timeout 15초와 일치 |
| `interface` | `PJSIP/1000` | 받지 않은 상담원 |
| `destuniqueid` | | 취소되는 채널 |

### AgentConnect — 통화 연결

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `holdtime` | `44` | 고객이 큐에서 기다린 시간. **초**. 재분배 시간까지 누적된다 |
| `ringtime` | `10` | 이번 상담원이 울린 시간. **초** |
| `interface` | `PJSIP/1000` | 받은 상담원 |
| `destuniqueid` | | 상담원 채널 |

### AgentComplete — 통화 종료

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `reason` | `caller` | **누가 끊었는가.** 고객이면 `caller` |
| `talktime` | `3` | 통화한 시간 (초) |
| `holdtime` | `44` | 대기했던 시간 (초). AgentConnect와 같은 값 |

`Hangup`이 오는 순서로는 누가 먼저 끊었는지 알 수 없다. 이 `reason`이 알려준다.

### QueueCallerAbandon — 포기호

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `holdtime` | `3` | 포기할 때까지 기다린 시간 (초) |
| `originalposition` | `1` | 큐에 처음 들어왔을 때의 순번 |
| `position` | `1` | 포기한 시점의 순번 |

두 순번을 비교하면 기다리는 동안 얼마나 앞으로 당겨졌는지 알 수 있다.

### QueueCallerLeave — 큐를 떠남

응답이든 포기든 큐를 벗어날 때 온다.

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `count` | `0` | 떠난 뒤 큐에 남은 인원 |
| `position` | `1` | 떠날 때의 순번 |

### DialBegin / DialState / DialEnd — 거는 쪽에서 본 호출

`Queue()`가 상담원을 부르는 것도 결국 다이얼이라 이 이벤트들도 함께 온다.

`DialBegin`

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `channel` | `PJSIP/1234-00000018` | 거는 쪽. 큐 콜에서는 고객 채널 |
| `destination` | `PJSIP/1000-00000019` | 받는 쪽 채널 |
| `destUniqueId` | `dev-1786776040.25` | 받는 쪽 uniqueid |
| `dialString` | `PJSIP/1000` | 실제로 다이얼한 문자열 |

`DialState` — 중간 상태

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `dialstatus` | `RINGING` | 상대가 울리기 시작함 |

`DialEnd`

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `dialStatus` | `ANSWER` / `NOANSWER` / `CANCEL` | 다이얼의 결과 |
| `connectedLineNum` | `1000` | 연결된 상대 번호 |

### 큐 콜에서는 Agent 이벤트를 쓴다

`Dial*`과 `Agent*`는 같은 일을 다른 관점에서 알려준다.

| 알고 싶은 것 | `Dial*` | `Agent*` |
|---|---|---|
| 상담원이 울리기 시작 | `DialBegin` | `AgentCalled` (+ `interface`, `queue`) |
| 응답함 | `DialEnd` `ANSWER` | `AgentConnect` (+ `holdtime`, `ringtime`) |
| 안 받음 | `DialEnd` `NOANSWER` | `AgentRingNoAnswer` (+ `ringtime`) |
| 고객이 포기 | `DialEnd` `CANCEL` | `QueueCallerAbandon` (+ `holdtime`) |

큐 콜에서는 오른쪽을 쓴다. 같은 사실에 큐와 상담원 정보가 함께 실려 오고,
결과가 이벤트 종류로 나뉘어 있어 `dialStatus`를 해석할 필요가 없다.

`Dial*`이 유일한 근거가 되는 경우도 있다.

- 서버가 거는 콜(클릭투콜) — 큐를 거치지 않으므로 `Agent*`가 오지 않는다
- 큐를 거치지 않는 직통 인바운드

그때 다시 볼 수 있도록 여기 적어 둔다.

### BridgeEnter / BridgeLeave — 음성이 오가는 구간

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `bridgeuniqueid` | `8ccfe00f-...` | 브리지 식별자 |
| `bridgenumchannels` | `1` → `2` → `1` → `0` | 그 시점 브리지 안의 채널 수 |
| `bridgetechnology` | `simple_bridge` → `native_rtp` | 연결 직후엔 Asterisk를 거치다가 단말끼리 직접 주고받도록 바뀐다 |

### Hangup — 채널 종료

| 값 | 관찰값 | 뜻 |
|---|---|---|
| `cause` | `16` | 종료 원인 코드 |
| `causeTxt` | `Normal Clearing` | 원인 설명 |

정상 종료는 `16`이다. 포기호에서는 `0`으로 왔다.

---

## 5. 함정

### Agent 이벤트의 주인공은 고객 채널이다

`AgentCalled`, `AgentConnect`, `AgentComplete`는 이름과 달리
**고객 채널에서 발생한 이벤트**다. 상담원 정보는 `dest`가 붙은 값에 들어 있다.

    AgentCalled
      channel      = PJSIP/1234-00000018   ← 고객
      uniqueid     = dev-1786776040.24     ← 고객
      destchannel  = PJSIP/1000-00000019   ← 상담원
      destuniqueid = dev-1786776040.25     ← 상담원

`getChannel()`을 부르면 상담원이 아니라 고객 채널이 나온다.
상담원 채널을 찾으려면 `getDestUniqueId()`를 봐야 한다.

### ringtime의 단위가 이벤트마다 다르다

| 이벤트 | 값 | 단위 |
|---|---|---|
| `AgentRingNoAnswer` | `15000` | 밀리초 |
| `AgentConnect` | `10` | 초 |

로그 타임스탬프와 대조해 확인했다. 무응답은 실제로 15초를 울렸고,
응답한 콜은 10.6초를 울렸다. 같은 이름인데 단위가 다르므로
그대로 더하거나 비교하면 안 된다.

`holdtime`과 `talktime`은 두 이벤트 모두 초다.

### DialEnd는 여러 번, 서로 다른 결과로 온다

| 상황 | dialStatus |
|---|---|
| 응답 | `ANSWER` (한 번) |
| 무응답 | `NOANSWER` → `CANCEL` |
| 포기호 | `CANCEL` → `CANCEL` |

응답일 때만 한 번이다. `DialEnd`가 왔다고 결과를 확정하지 말고
`dialStatus`를 봐야 한다.

### DialBegin은 라이브러리가 하나 더 만든다

asterisk-java가 구버전 호환용 복사본을 추가로 발행한다.

    private void dispatchLegacyEventIfNeeded(ManagerEvent event, ...) {
        if (event instanceof DialBeginEvent) {
            DialEvent legacyEvent = new DialEvent((DialBeginEvent) event);
            dispatchEvent(legacyEvent, ...);
        }
    }

로그에서 `dialString`이 비어 있는 쪽이 복사본이다.
`DialEnd`에는 이 동작이 없다. 위의 여러 번 오는 것과는 다른 이야기다.

### HangupEvent의 toString에는 채널이 안 보인다

    HangupEvent [cause=16, causeTxt=Normal Clearing, language=en, linkedId=..., accountCode=]

channel과 uniqueid가 없다. 하지만 `getChannel()`, `getUniqueId()`에는 값이 들어 있다.

> toString에 없는 것과 값이 없는 것은 다르다.

### 노이즈가 많다

`VarSet`, `RtcpSent`, `RtcpReceived`, `DeviceStateChange`, `NewConnectedLine`은
통화 한 건에 수십 개씩 온다. 구독할 이벤트를 좁혀서 봐야 한다.
