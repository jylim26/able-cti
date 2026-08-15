# 콜 조립

Date: 2026-08-15

## Goal

`call` 모듈은 채널 단위의 AMI 이벤트를 통화 단위의 Call로 조립한다.

이번 단계의 범위는 큐 인바운드 콜을 알아보고, 채널들을 묶고,
끝나는 시점을 잡는 것까지다.
상태 머신(RINGING/QUEUED/CONNECTED...), 이벤트 발행, 아웃바운드,
재동기화는 이후 단계에서 다룬다.

무엇을 기준으로 콜을 알아보는지는 두 ADR에 있다.

- [ADR-0002](../adr/0002-linkedid-as-call-id.md) — 통화 식별자는 linkedid
- [ADR-0003](../adr/0003-dialplan-optin-tracking.md) — 추적할 콜은 dialplan이 알려준다

---

## Structure

    AMI Events (asterisk-java 타입)
         ↓
    AmiCallEventTranslator     번역기. asterisk-java 타입은 여기까지만 온다
         ↓
    CallRegistry               지금 진행 중인 통화 목록 (linkedid → Call)
         ↓
    Call / CallLeg             통화와 채널. asterisk-java를 모른다

`AmiCallEventTranslator`는 `ManagerEventListener`를 구현한 `@Component`라서
`AmiConnectionManager`가 자동으로 수집해 AMI 연결에 등록한다.

번역기의 왼쪽은 Asterisk의 말(Newchannel, linkedid)로,
오른쪽은 업무의 말(콜 시작, 채널 종료)로 이야기한다.
이 경계 덕에 이후 추가될 모듈(화면 푸시, DB 기록)은 Asterisk를 몰라도 된다.

---

## 이벤트 처리 규칙

큐 인바운드 한 건에서 이벤트가 오는 순서와, 각 이벤트에서 하는 일이다.

| 순서 | AMI 이벤트 | 처리 |
|---|---|---|
| 1 | `Newchannel` (고객 채널) | 버린다. 아직 추적 대상인지 모른다 |
| 2 | `UserEvent(CtiCallStarted)` | Call을 만들고 고객 채널을 붙인다. 통화가 생기는 유일한 입구 |
| 3 | `QueueCallerJoin` | 로그만 남긴다 (상태 머신 단계에서 QUEUED 전이로 바뀔 자리) |
| 4 | `Newchannel` (상담원 채널) | 이미 있는 Call에 채널을 붙인다 |
| 5 | `AgentConnect` | 로그만 남긴다 (상태 머신 단계에서 CONNECTED 전이로 바뀔 자리) |
| 6 | `Hangup` × 2 | 채널을 종료 처리한다. 마지막 채널이면 통화 종료, 목록에서 제거 |

판별식은 두 개뿐이다.

- **첫 채널인가**: `uniqueid == linkedid`.
  통화의 첫 채널만 이 둘이 같다는 것이 Asterisk의 규칙이다.
- **추적 중인 통화인가**: 목록(registry)에 그 linkedid가 있는가.
  없으면 조용히 무시한다.

`CtiCallStarted`를 받으면 세 가지를 확인하고 통과할 때만 통화를 만든다.

1. linkedid가 있는가
2. 첫 채널에서 온 것인가 (나중에 생긴 채널의 표식으로는 통화를 만들지 않는다)
3. Direction이 INBOUND인가

`Hangup`은 채널마다 한 번씩 온다. 2채널 통화라면 두 번 온다.
그래서 채널 하나가 끊겼다고 통화를 끝내지 않고,
살아 있는 채널이 하나도 없어질 때만 통화 종료로 본다.

---

## 같은 Newchannel인데 왜 하나는 버리고 하나는 받는가

위 표에서 `Newchannel`이 두 번 나온다. 1번은 버리고 4번은 받는다.
둘을 가르는 것은 이벤트가 아니라 **그 시점의 registry 상태**다.

    registry.find(e.getLinkedid()).ifPresent(call -> {
        call.legStarted(e.getUniqueId(), e.getChannel());
    });

`ifPresent`는 "찾았을 때만 이 블록을 실행한다"는 뜻이다.
즉 목록에 있는 통화의 채널만 받는다.

| 시점 | registry 상태 | 결과 |
|---|---|---|
| 1. 고객 채널 Newchannel | 아직 비어 있음 | 못 찾는다. 버려진다 |
| 2. CtiCallStarted | Call을 만들어 넣음 | — |
| 4. 상담원 채널 Newchannel | 그 linkedid가 있음 | 찾는다. 채널이 붙는다 |

번역기의 다른 핸들러도 전부 이 모양이다.
**찾았을 때만 일한다. 못 찾으면 아무 일도 안 일어난다.**

### 첫 채널을 버려도 되는 이유

Asterisk는 UserEvent를 보낼 때 그 채널의 정보를 자동으로 붙여준다.
Channel, Uniqueid, Linkedid, CallerIDNum, Exten이 전부 들어 있다.

    Call call = Call.start(linkedid, e.getCallerIdNum(), e.getExten());
    call.legStarted(e.getUniqueId(), e.getChannel());   // 1번에서 버린 그 채널

그래서 2번이 1번의 내용을 그대로 갖고 온다. 버려도 잃는 것이 없다.

### 순서가 뒤집힐 걱정은 없다

채널이 만들어져야 dialplan이 실행되고,
dialplan이 실행돼야 `UserEvent`가 나간다.
1번이 2번보다 늦게 오는 경우는 없다.

### 추적하지 않는 콜도 같은 자리에서 걸러진다

1000 → 1001 내선 통화에는 `CtiCallStarted`가 없다.
그래서 그 통화의 linkedid는 목록에 영영 들어가지 않는다.

결과적으로 그 통화의 모든 `Newchannel`, `Hangup`이 `ifPresent`에서 걸러진다.
"추적하지 않는다"를 위한 별도 코드가 없는 이유다.

---

## 실제로 담기는 모양

큐 인바운드 한 건이 진행되는 동안 registry 안의 변화다.

**① 고객이 걸고 UserEvent 도착**

    calls
    └── "1755000000.100" → Call
                           ├── callerNumber = "1234"
                           ├── calledNumber = "0212345678"
                           └── legs
                               └── "1755000000.100" → PJSIP/1234-00000001  alive=true

통화의 linkedid와 첫 채널의 uniqueId가 같다.

**② 큐가 상담원을 호출**

    legs
    ├── "1755000000.100" → PJSIP/1234-00000001  alive=true
    └── "1755000000.101" → PJSIP/1000-00000002  alive=true

uniqueId는 다르고 linkedid는 같아서 이 통화를 찾아 붙는다.

**③ 상담원이 먼저 끊음**

    legs
    ├── "1755000000.100" → PJSIP/1234-00000001  alive=true
    └── "1755000000.101" → PJSIP/1000-00000002  alive=false   ← 표시만

살아 있는 채널이 남아서 통화는 유지된다.

**④ 고객도 끊음**

    calls
    └── (비어 있음)

살아 있는 채널이 0이 되면 통화 종료로 보고 목록에서 지운다.

맵이 두 겹으로 겹쳐 있는 구조다.

| 맵 | 키 | 값 | 의미 |
|---|---|---|---|
| `CallRegistry.calls` | linkedid | Call | 지금 진행 중인 통화들 |
| `Call.legs` | uniqueId | CallLeg | 그 통화에 속한 채널들 |

바깥 맵은 통화가 끝나면 지운다.
안쪽 맵은 채널이 끊겨도 지우지 않고 `alive=false`로 표시만 남긴다.
몇 개가 살아 있는지 세야 하기 때문이다.

---

## 규율

1. **생김새로 추측하지 않는다.**
   채널 이름이나 번호가 어떻게 생겼는지를 보고 방향이나 역할을 정하지 않는다.
2. **번역기 안에서 생긴 예외는 밖으로 던지지 않는다.**
   `onManagerEvent`가 전부 잡아서 로그로 남긴다.
   여기서 예외가 새어 나가면 이벤트를 받아오는 스레드가 죽는다.
3. **한 통화의 처리는 한 번에 하나씩만 실행된다.**
   Call의 메서드는 `synchronized`다.

   지금은 이 잠금이 막는 상황이 없다. AMI 이벤트는 asterisk-java의 리더 스레드
   하나가 순서대로 전달하므로 Call을 건드리는 스레드가 하나뿐이다.

   대비하는 것은 두 번째 스레드가 생기는 시점이다. 화면에서 보류·전환을 누르면
   웹 요청 스레드가 같은 통화를 건드리고, 재동기화와 화면 푸시도 별도 스레드에서
   돈다. 그때 생기는 문제는 두 가지다.

   | 문제 | 내용 |
   |---|---|
   | 경합 | 두 스레드가 동시에 채널 목록을 고쳐서 자료구조가 깨진다 |
   | 가시성 | 한 스레드가 바꾼 값을 다른 스레드가 보지 못한다. 동시에 실행되지 않아도 생긴다 |

### asterisk-java 이름 함정

asterisk-java는 AMI 헤더 이름으로 setter를 찾아 값을 채운다.
그래서 이름이 곧 헤더와의 계약이고, **컴파일이 되는 것과 값이 채워지는 것은 별개다.**
이름이 틀리면 에러 없이 null이 들어온다.

라이브러리가 만든 이벤트는 같은 헤더인데도 클래스마다 이름이 다르다.
새 이벤트를 구독할 때는 javap로 먼저 확인한다.

| 이벤트 | linkedid getter |
|---|---|
| `NewChannelEvent` | `getLinkedid()` |
| `HangupEvent` | `getLinkedId()` |
| `QueueCallerJoinEvent` | `getLinkedId()` |
| `AgentConnectEvent` | `getLinkedId()` |

우리가 만드는 `CtiCallStartedEvent`는 필드 이름이 헤더 이름이 된다.
`linkedid` 필드가 `Linkedid` 헤더를, `direction` 필드가 `Direction` 헤더를 받는다.
setter는 asterisk-java가 값을 채우는 통로라 반드시 있어야 하므로
`@Getter`·`@Setter`로 만든다.

이 클래스에는 linkedid와 direction만 선언한다.
callerIdNum, exten, channel, uniqueId는 Asterisk가 자동으로 붙여주는 채널 정보라
상위 클래스(`ManagerEvent`)의 필드로 채워진다.

---

## Verification

1. `./gradlew test` — 번역기 단위 테스트.
   테스트 데이터는 실제 AMI 이벤트가 오는 순서를 그대로 따른다.
   필드를 손으로 채워 넣으면 실제 경로에서만 생기는 결함이 가려진다.
2. 실통화 검증. 준비:

        docker compose exec asterisk asterisk -rx "queue add member PJSIP/1000 to queue01"

---

## 실통화 검증 결과

Date: 2026-08-15

내선 3개(1000·1001 상담원, 1234 고객)를 등록하고 `PJSIP/1000`을 queue01에
넣은 상태에서 확인했다.

| 시나리오 | 기대 | 결과 |
|---|---|---|
| 1234 → `0212345678`, 고객이 먼저 끊음 | 통화 조립 후 종료 | 통과 |
| 1234 → `0212345678`, 상담원이 먼저 끊음 | 같은 결과 | 통과 |
| 포기호 — 상담원이 받기 전에 고객이 끊음 | 통화가 목록에서 지워짐 | 통과 |
| 무응답 재분배 — 두 상담원이 안 받고 재호출로 응답 | 중간에 끝나지 않음 | 통과 |
| 1000 → 1001 내선 통화 | 통화가 생기지 않음 | 통과 |
| `600` 에코 테스트 | 통화가 생기지 않음 | 통과 |

각 시나리오에서 실제로 온 이벤트는
[큐 콜에서 오는 AMI 이벤트](../domain/queue-call-events.md)에 정리했다.

큐 인바운드에서 실제로 찍힌 로그다.

    call started:    linkedid=dev-1786775552.13 caller=01012345678 called=0212345678
    queue joined:    linkedid=dev-1786775552.13 queue=queue01 position=1
    leg started:     linkedid=dev-1786775552.13 channel=PJSIP/1000-0000000e
    agent connected: linkedid=dev-1786775552.13 interface=PJSIP/1000
    leg ended:       linkedid=dev-1786775552.13 channel=PJSIP/1000-0000000e
    leg ended:       linkedid=dev-1786775552.13 channel=PJSIP/1234-0000000d
    call ended:      linkedid=dev-1786775552.13

`caller`가 `01012345678`로 찍히는 것은 개발환경에서 1234 단말의 callerid를
`Customer <01012345678>`로 넣어두었기 때문이다. 통신사가 넘겨주는 고객 번호
자리를 흉내낸 것이다.

### 상담원 채널이 항상 먼저 끝난다

누가 먼저 끊든 `PJSIP/1000` 채널의 `leg ended`가 먼저 나온다.

고객 채널은 `Queue()`를 **실행 중인** 채널이고, 상담원 채널은 그 `Queue()`가
**만들어낸** 채널이기 때문이다.

    고객 채널 ── Queue() 실행 중
                    └── 상담원 채널을 만들어 연결

| 누가 끊는가 | 일어나는 일 |
|---|---|
| 상담원 | 상담원 채널 종료 → `Queue()`가 끝남 → 고객 채널이 다음 줄로 진행해 종료 |
| 고객 | 아직 `Queue()` 안이라 자기가 만든 상담원 채널부터 정리 → 그 다음 고객 채널 종료 |

둘 다 "Queue가 만든 것이 먼저 정리되고, Queue를 실행하던 채널이 나중에 끝난다"다.

이 순서에 기대지 않는 것이 중요하다.
"고객 채널이 끊기면 통화 종료"로 만들었다면 여기서 통화가 일찍 끝났을 것이다.
살아 있는 채널을 세는 방식이라 순서와 무관하게 동작한다.

### 채널이 죽어도 통화는 유지된다

무응답 재분배에서 한 통화에 채널이 네 개까지 생겼다.

    leg started   PJSIP/1000-0000001b   상담원1 호출
    leg ended     PJSIP/1000-0000001b   15초 무응답        ← 통화는 유지
    leg started   PJSIP/1001-0000001c   상담원2 호출
    leg ended     PJSIP/1001-0000001c   15초 무응답        ← 여전히 유지
    leg started   PJSIP/1000-0000001d   상담원1 재호출
    agent connected                     응답
    leg ended × 2 → call ended                            ← 딱 한 번

채널이 두 번 죽는 동안 고객 채널이 살아 있어 개수가 0이 되지 않았다.
"채널 하나가 끊기면 통화 종료"였다면 첫 무응답에서 통화가 사라졌을 것이다.

### 아직 확인하지 않은 경로

- 상담원 여러 명이 동시에 울리는 분배 (`ringinuse`, 전략 변경)
- 큐에 여러 통화가 동시에 대기하는 경우
