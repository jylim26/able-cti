# Asterisk 콜 모델

Date: 2026-08-14

콜 도메인을 구현하기 전에 정리한 개념 노트다.

AMI 이벤트를 통화 단위로 조립하려면 Asterisk가 통화를 어떤 단위로 쪼개서 보는지
먼저 알아야 한다.

---

## 1. 채널과 콜은 다르다

가장 먼저 잡아야 할 구분이다.

- 채널(channel)은 단말 하나와 Asterisk 사이의 연결 1개다.
- 콜(call)은 채널 여러 개가 묶인 한 건의 통화다.

        고객 ──채널A── Asterisk ──채널B── 상담원

        채널 2개, 콜 1개

통화 중인 전화기 수만큼 채널이 존재한다. 3자 통화라면 채널이 3개다.

식별자는 두 가지다.

| 식별자 | 단위 | 설명 |
|---|---|---|
| `uniqueid` | 채널 | 채널 하나의 고유 ID |
| `linkedid` | 콜 | 같은 통화에 속한 채널들이 공유하는 ID |

`linkedid`는 그 통화에서 처음 만들어진 채널의 `uniqueid`와 같다.

> Asterisk의 거의 모든 이벤트는 채널 단위다. 콜 단위로 보려면 누군가 `linkedid`로 묶어야 한다.

그 묶는 작업이 CTI 서버가 하는 일의 절반이다.

---

## 2. 브리지

채널이 만들어졌다고 통화가 되는 것은 아니다. 음성이 오가려면 채널들이 브리지에 들어가야 한다.

브리지(bridge)는 채널들을 묶어 음성을 주고받게 하는 지점이다.

        채널A ─┐
               ├── Bridge ── 음성 교환
        채널B ─┘

관련 이벤트는 두 개다.

| 이벤트 | 시점 |
|---|---|
| `BridgeEnter` | 채널이 브리지에 들어옴 |
| `BridgeLeave` | 채널이 브리지에서 빠짐 |

브리지를 보면 알 수 있는 것이 있다.

- 벨이 울리는 중인지 실제로 연결됐는지 구분된다. 채널 생성만으로는 알 수 없다.
- 보류는 상대 채널이 브리지에서 빠지는 것으로 나타난다.
- 호전환은 브리지 구성이 바뀌는 것으로 나타난다.

---

## 2.1 받는 쪽 채널은 거는 쪽이 만든다

여기서 오해하기 쉬운 지점이 하나 있다.

> 전화를 받는 쪽 채널은 원래 있던 것이 아니라, 거는 쪽이 새로 만든 것이다.

`Dial(PJSIP/1001)`이 실행되면 Asterisk는 1001에게 **전화를 건다**. 그 결과로
1001 채널이 새로 생긴다. 두 채널이 다 준비되면 브리지로 묶인다.

    채널A(거는 쪽)
       │ Dial 실행
       ▼
    채널B 생성 → 벨 → 받음
       │
       ▼
    Bridge에 둘 다 들어감 → 음성 교환

### 큐도 마찬가지다

`Queue()`는 고객을 상담원에게 연결해 주는 특별한 장치가 아니다.
**고객 채널이 상담원에게 전화를 거는 것**이고, 큐가 하는 일은
그 다이얼 대상을 누구로 할지 고르는 것(분배 전략)이다.

    고객 채널 ── Queue() 실행
                   │
                   ├─ 상담원을 골라 전화를 건다 (채널 생성)
                   │
                   └─ 받으면 두 채널을 브리지로 묶는다

연결이 일어나는 지점은 큐가 아니라 브리지다.

상담원 채널의 이벤트를 보면 Asterisk가 그렇게 부르고 있다.

    NewChannel   channel='PJSIP/1000-0000001b'  state='Down'   ← 아직 안 걸린 새 채널
    NewExten     application='AppQueue'  appdata='(Outgoing Line)'
    DialBegin    dialString='PJSIP/1000'

`(Outgoing Line)` — 나가는 회선이다.

### 이 관점으로 보면 설명되는 것들

| 현상 | 이유 |
|---|---|
| 무응답 후 재호출하면 채널이 새로 생긴다 | 매번 새로 거는 것이니 새 채널이다 |
| 상담원 채널이 항상 먼저 끊긴다 | `Queue()`가 자기가 건 통화를 먼저 정리한다 |
| `AgentCalled`의 `channel`이 상담원이 아니라 고객이다 | 거는 주체가 고객 채널이다 |
| 상담원 채널의 context가 `from-internal`이다 | 상담원 단말에 걸기 위해 그 단말의 설정을 쓴다 |

상담원 전화기 입장에서는 "전화가 왔다"지만 Asterisk 입장에서는
"상담원에게 전화를 걸었다"다. 같은 일을 양쪽에서 다르게 보는 것뿐이다.

---

## 3. context — 어디로 들어왔는가

Asterisk 설정은 역할별로 나뉘어 있다.

| 파일 | 답하는 질문 | 단위 |
|---|---|---|
| `pjsip.conf` | 누구인가, 어디 있는가 | endpoint, auth, aor |
| `extensions.conf` | 무엇을 실행할 것인가 | context 안의 exten 규칙 |

두 파일을 잇는 것이 context 이름이다.

endpoint에 `context = from-trunk`라고 적으면, 그 단말이 건 콜은 `extensions.conf`의
`[from-trunk]` 섹션에서만 번호를 검색한다.

        1002가 100을 누름
          ↓
        pjsip.conf     인증. 1002의 context는 from-internal
          ↓
        extensions.conf  [from-internal]에서 100 검색, 실행
          ↓
        Dial(PJSIP/1001)
          ↓
        pjsip.conf     1001의 aor에서 실제 위치 조회
          ↓
        1001이 울림

여기서 중요한 것은 context가 단순한 네임스페이스가 아니라는 점이다.

> 같은 번호라도 어느 문으로 들어왔느냐에 따라 다르게 처리할 수 있다.

context는 AMI 이벤트에도 실려 온다. 따라서 CTI 서버는 번호 생김새로 추측하지 않고
사실 기반으로 외부 인입과 내부 통화를 구분할 수 있다.

### 3.1 context는 발신 쪽에서만 쓰인다

혼동하기 쉬운 지점이다.

> context는 그 endpoint가 번호를 눌러 콜을 시작할 때, 그 번호를 검색할 구역을 정한다.
> 콜을 받는 쪽에서는 쓰이지 않는다.

받을 때는 번호를 찾을 일이 없기 때문이다. 이미 `Dial(PJSIP/1001)`이라는 결론이 난
뒤에 호출될 뿐이다.

내선 1001의 context가 `from-internal`이라고 할 때 두 경우를 비교하면 분명해진다.

경우 A. 1001이 1002에게 건다.

    1001이 1002를 누름
      ▼
    1001의 context는 from-internal
      ▼
    [from-internal]에서 1002 검색        ← 1001의 context 사용
      ▼
    Dial(PJSIP/1002)

경우 B. 고객이 1001에게 건다.

    고객이 021234001을 누름 (통신사 경유)
      ▼
    트렁크의 context는 from-trunk
      ▼
    [from-trunk]에서 021234001 검색      ← 트렁크의 context 사용
      ▼
    Dial(PJSIP/1001)                     ← 1001은 울릴 대상일 뿐
      ▼
    1001 벨

경우 B에서 1001의 `from-internal`은 한 번도 쓰이지 않는다.

| 상황 | 실행되는 context | 1001의 역할 |
|---|---|---|
| 1001이 건다 | `from-internal` (1001의 것) | 발신자 |
| 고객이 1001을 받는다 | `from-trunk` (트렁크의 것) | 수신 대상 |

내선 하나가 외부 콜도 받고 내선 통화도 거는 이유다. 받는 데는 context가 필요 없다.

CTI가 인바운드를 판별할 때 보는 것도 언제나 발신 쪽 채널의 context다. 상담원 단말의
context는 전부 `from-internal`이라 아무것도 구분하지 못한다.

### 3.2 현재 환경의 context

| context | 용도 |
|---|---|
| `from-trunk` | 외부에서 들어온 콜 |
| `from-internal` | 내선에서 나간 콜 |
| `Hold` | 보류 처리 |

인입 context는 통신사 이중화나 국제/국내 분리로 2~4개까지 늘어나는 것이 일반적이다.
설정을 목록(`inbound-contexts`)으로 두는 이유다.

---

## 4. 외부 콜은 어떻게 들어오는가

3절의 `from-trunk`가 실제로 어디서 오는지 정리한다.

DID(Direct Inward Dialing)는 통신사가 우리 조직에 할당한 외부 번호다. 예를 들어
`1588-0000`이 그것이다.

### 4.1 등록되는 것은 고객이 아니라 트렁크다

고객은 `pjsip.conf`에 등록되지 않는다. Asterisk는 고객이 누구인지 모른다.

등록되는 것은 통신사 트렁크 하나이며, context는 거기에 붙어 있다.

    [trunk-kt]
    type = endpoint
    context = from-trunk

고객 번호는 INVITE 메시지 안에 실려 온다.

### 4.2 순서

    ① 고객이 1588-0000을 누른다
         ▼
    ② 통신사 망이 번호 소유자를 조회해 우리 회선으로 라우팅한다
         ▼
    ③ 통신사 SBC가 우리 Asterisk로 SIP INVITE를 보낸다
         ▼
    ④ Asterisk가 발신지 IP로 endpoint를 식별한다
         ▼
    ⑤ 식별된 endpoint의 context를 읽는다 (from-trunk)
         ▼
    ⑥ [from-trunk]에서 DID 번호로 exten을 검색한다
         ▼
    ⑦ 매칭된 규칙을 실행한다 (Queue(sales))

Asterisk는 "통신사에서 왔다"를 인식하지 않는다. 발신지를 먼저 확정하고, 그 endpoint의
설정에서 context를 꺼낸다.

### 4.3 INVITE의 두 번호

    INVITE sip:15880000@211.x.x.x SIP/2.0
    From: <sip:01012345678@kt.example.com>
    To:   <sip:15880000@211.x.x.x>

| 헤더 | 값 | 쓰임 |
|---|---|---|
| Request-URI / To | DID 번호 | 다이얼플랜에서 무엇을 실행할지 결정 |
| From | 고객 번호 | CallerID. 화면 표시와 기록 |

exten 검색에 쓰이는 것은 DID 번호다. 고객 번호는 검색에 관여하지 않는다.

### 4.4 트렁크 식별 방식

내선과 다르다.

- 내선은 단말이 REGISTER로 등록하고 비밀번호로 인증한다.
- 트렁크는 발신지 IP로 식별한다. 통신사 SBC의 IP를 미리 적어둔다.

        [trunk-kt]
        type = identify
        endpoint = trunk-kt
        match = 통신사 SBC IP

통신사와 계약할 때 서로 IP를 교환하는 이유다.

### 4.5 DID를 여러 개 받을 때

같은 트렁크로 들어와도 DID 번호에 따라 갈래가 나뉜다.

    [from-trunk]
    exten => 15880000,1,Queue(sales)
    exten => 15880001,1,Queue(support)
    exten => 021234001,1,Dial(PJSIP/1001)

통신사가 넘기는 번호 형식은 사업자마다 다르다. 전체 번호, 뒷자리만, 국가번호 포함이
모두 가능하므로 계약 시 협의한다. 형식이 맞지 않으면 다이얼플랜에서 잘라 쓴다.

    exten => _X.,1,Set(DID=${EXTEN:-4})

### 4.6 개발환경에서의 대체

현재 환경에는 통신사 트렁크가 없다. 대신 내선 하나에 `context = from-trunk`를 주어
외부 인입을 흉내낸다.

| 구분 | 실제 운영 | 현재 환경 |
|---|---|---|
| `from-trunk`를 가진 endpoint | 통신사 트렁크 | 고객 시뮬레이터 내선 |
| 고객 번호 | INVITE의 From 헤더 | 시뮬레이터 내선 번호 |

이 단말로 걸면 Asterisk와 CTI 서버 모두 외부 인입과 동일하게 처리한다.

---

## 5. 주요 AMI 이벤트

콜 도메인에서 다루는 이벤트다.

| 이벤트 | 뜻 | 함께 오는 정보 |
|---|---|---|
| `Newchannel` | 채널 생성 | context, uniqueid, linkedid |
| `QueueCallerJoin` | 큐 진입 | 큐 이름, 대기 순번 |
| `AgentConnect` | 큐가 상담원을 연결 | 상담원 채널 |
| `DialBegin` / `DialEnd` | Dial 시작과 종료 | DialStatus |
| `BridgeEnter` / `BridgeLeave` | 브리지 출입 | 브리지 ID |
| `Hangup` | 채널 종료 | 종료 원인 코드 |

`Hangup`은 채널마다 온다. 2채널 통화라면 두 번 온다. 콜이 끝났다고 판단하려면
마지막 채널이 내려가는 시점을 세야 한다.

순서는 누가 먼저 끊었는지를 알려주지 않는다. 큐 콜을 실측해 보면 고객이 먼저
끊어도 상담원 채널의 `Hangup`이 먼저 온다. `Queue()`가 자기가 만든 채널을 먼저
정리하고 나서 자신이 실행되던 채널이 끝나기 때문이다.

누가 끊었는지는 큐 콜이라면 `AgentComplete`의 `reason`이 알려준다. 관찰한
이벤트는 [큐 콜에서 오는 AMI 이벤트](queue-call-events.md)에 정리했다.

---

## 6. 콜 흐름

수신자 쪽 처리는 어느 시나리오나 동일하다. `Dial(PJSIP/1001)` 이후는 같다.

달라지는 것은 발신자가 어느 context에서 출발했는가뿐이다.

### 6.1 내선 통화 (1002 → 1001)

        전화기 1002
          │ INVITE 1001
          ▼
        pjsip.conf        1002의 context = from-internal
          ▼
        [from-internal]   _1XXX 매칭, Dial(PJSIP/1001)
          ▼
        전화기 1001       벨

CTI 관점에서는 `Newchannel`의 context가 인입 context가 아니므로 추적하지 않는다.

### 6.2 DID 직통 인바운드 (고객 → 021234001 → 1001)

        고객
          │ INVITE 021234001 (통신사 경유)
          ▼
        pjsip.conf        발신지 IP로 트렁크 식별, context = from-trunk
          ▼
        [from-trunk]      021234001 매칭, Dial(PJSIP/1001)
          ▼
        전화기 1001       벨

6.1과 나란히 놓고 보면 수신자 쪽은 완전히 같다. 발신자의 context 하나가
다이얼플랜 구역과 CTI 판별을 갈랐다.

### 6.3 큐 인바운드 (고객 → 15880000 → sales 큐 → 1001)

        고객
          │ INVITE 15880000 (통신사 경유)
          ▼
        pjsip.conf        발신지 IP로 트렁크 식별, context = from-trunk
          ▼
        [from-trunk]      Answer → MixMonitor(녹취) → Queue(sales)
          ▼
        Queue(sales)      대기
          ▼
        전화기 1001       상담원 호출, 응답 시 브리지

CTI가 받는 이벤트 순서는 다음과 같다.

1. `Newchannel` (context=from-trunk) — 인바운드 콜 시작
2. `QueueCallerJoin` — 대기 진입
3. `AgentConnect` — 상담원 연결
4. `Hangup` × 2 — 마지막 채널에서 콜 종료

### 6.4 요약

| 시나리오 | 발신 context | 다이얼플랜 구역 | CTI 판별 |
|---|---|---|---|
| 내선 통화 | `from-internal` | `[from-internal]` | 추적하지 않음 |
| DID 직통 | `from-trunk` | `[from-trunk]` | 인바운드 |
| 큐 인바운드 | `from-trunk` | `[from-trunk]` | 인바운드 |

판별 근거는 언제나 발신 경로의 속성이지 수신 단말의 속성이 아니다.

서버가 거는 콜(클릭투콜)은 발신자가 전화기가 아니라 애플리케이션이므로 판별 방식이
다르다. 호 제어에서 다룬다.

---

## 7. 콜 도메인이 풀어야 할 문제

지금까지의 개념이 그대로 과제가 된다.

| 문제 | 근거로 쓸 것 |
|---|---|
| 어느 채널들이 같은 통화인가 | `linkedid` |
| 이 콜을 추적할 것인가 | `Newchannel`의 context |
| 언제 응답으로 볼 것인가 | 큐 콜은 `AgentConnect` |
| 언제 종료로 볼 것인가 | 마지막 채널의 `Hangup` |

응답 판정은 방향에 따라 근거가 다르다. 큐 콜은 `AgentConnect`, 서버 발신 콜은
`DialEnd`를 본다. 서로 남의 이벤트에 반응하면 응답이 중복 처리되므로 방향으로
먼저 걸러야 한다.

큐를 거치지 않는 직통 인바운드는 두 이벤트 모두 해당하지 않는다. 응답 시점을
잡을 근거가 없다는 뜻이고, 콜 도메인 설계에서 결정해야 할 사항이다.
