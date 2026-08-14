# ADR-0001: Asterisk 제어는 AMI를 사용하고, 콜 분배는 dialplan에 맡긴다

Status: Accepted  
Date: 2026-08-14

## Context

Asterisk를 Java 애플리케이션에서 제어하는 방법으로 AMI와 ARI를 검토했다.

| | AMI | ARI |
|---|---|---|
| 통신 | TCP 5038 / 텍스트 프로토콜 | HTTP REST + WebSocket |
| 이벤트 범위 | Asterisk 시스템 전체 | Stasis 앱에 진입한 채널 |
| 통화 제어 주체 | Asterisk | 애플리케이션 |
| ACD | `app_queue` 사용 가능 | 애플리케이션에서 직접 구현 |

프로토콜 자체는 ARI가 더 현대적이다.

하지만 이번 결정에서 중요한 것은 API 형태가 아니라 다음 질문이다.

> 걸려온 전화를 어느 상담원에게 연결할 것인가를 누가 책임질 것인가?

IVR은 별도 엔진에서 처리하므로 이번 ADR의 범위에서는 제외한다.

---

## Decision

다음과 같이 결정한다.

- Java CTI 서버는 AMI를 사용한다.
- 콜 라우팅과 ACD는 Asterisk dialplan + `app_queue`가 담당한다.
- 외부 IVR은 필요에 따라 ARI 또는 AGI를 사용할 수 있다.
- IVR과 CTI 사이의 경계는 `Queue()`에 진입하는 지점으로 정의한다.

즉,

    IVR
     ↓
    Queue()
     ↓
    Asterisk app_queue
     ↓
    Agent

CTI 서버는 통화를 직접 소유하지 않는다.

CTI의 책임은 크게 두 가지다.

1. Asterisk 이벤트를 수신해 통화 상태를 구성한다.
2. 필요한 제어 명령을 Asterisk에 전달한다.

---

## Why

결정에 가장 큰 영향을 준 것은 두 가지다.

| 기준 | 선택 | 이유 |
|---|---|---|
| ACD 구현 복잡도 | AMI | `app_queue`를 그대로 사용할 수 있음 |
| CTI 장애 시 통화 지속성 | AMI | 통화가 CTI 프로세스와 독립적으로 유지됨 |

ARI는 통화 상태를 명확하게 모델링하기 쉽다는 장점이 있지만,
이번 시스템에서는 위 두 조건보다 우선하지 않는다.

### 1. ACD를 직접 구현하지 않는다

ARI 기반으로 ACD를 구현하면 다음 기능을 애플리케이션에서 직접 만들어야 한다.

- 대기열과 순서 관리
- 포기호 처리
- 상담원 가용 상태
- penalty / skill level
- 분배 전략
  - `rrmemory`
  - `leastrecent`
  - `fewestcalls`
- 상담원 응답 timeout
- 다음 상담원으로의 재분배
- 대기음 및 순번 안내
- bridge 생성 및 channel 추가/제거

이 기능들은 이미 Asterisk의 `app_queue`에 구현되어 있다.

dialplan에서는 사실상 다음 호출로 끝난다.

    Queue(...)

CTI 서버가 필요한 것은 ACD 구현 자체가 아니라 이벤트 관찰과 제어이므로
AMI가 책임 범위에 더 적합하다.

---

### 2. CTI 서버 장애가 통화를 끊어서는 안 된다

ARI에서는 `Stasis()`에 들어간 이후의 통화를 애플리케이션이 직접 제어한다.

    Channel
      ↓
    Stasis
      ↓
    ARI Application
      ↓
    Bridge / Dial / Hangup

따라서 ARI 애플리케이션이 종료되면 애플리케이션이 관리하던 통화 상태도 영향을 받는다.

특히 ACD까지 애플리케이션에서 구현할 경우 다음 상태가 애플리케이션 메모리에 존재하게 된다.

- 대기열 순서
- 상담원 선택 상태
- bridge 구성
- 현재 분배 단계

CTI 서버의 재배포나 장애가 진행 중인 통화에 직접적인 영향을 줄 수 있다.

반면 AMI에서는 통화가 Asterisk 내부에서 계속 실행된다.

    Caller
       │
       ▼
    Asterisk ── Queue() ── Agent
       │
       └──── AMI ──── CTI Server

CTI 서버가 종료되어도 끊어지는 것은 AMI TCP 연결뿐이다.

따라서:

- 고객 ↔ 상담원 통화는 유지된다.
- `Queue()`는 계속 실행된다.
- `MixMonitor` 녹취도 계속된다.
- CTI 화면 갱신과 제어 명령만 일시적으로 중단된다.

CTI 서버가 다시 올라오면 다음 명령으로 현재 상태를 다시 조회한다.

    CoreShowChannels
    QueueStatus

완전한 복구가 가능한 것은 아니다.

장애 시간 동안 시작해서 종료된 통화는 실시간 이벤트를 복구할 수 없으며,
처리 중이던 DB 상태가 일부 남을 수 있다.

---

## Consequences

AMI를 선택함으로써 얻는 장점만 있는 것은 아니다.

가장 큰 비용은 AMI 이벤트를 통화 단위로 재구성하는 상태 머신이 필요하다는 것이다.

AMI가 제공하는 이벤트는 통화가 아니라 channel 중심이다.

예:

    Newchannel   SIP/trunk-0001
    Newchannel   PJSIP/1001-0002
    DialBegin
    BridgeEnter
    BridgeEnter
    Hangup

여기에는 "한 건의 통화"라는 개념이 직접 존재하지 않는다.

CTI 서버는 이벤트를 조합해 다음 정보를 추론해야 한다.

| 필요한 정보 | 판단 방법 |
|---|---|
| 같은 통화에 속한 채널인가 | `linkedid` |
| Call ID | `linkedid`를 기준으로 생성 |
| Inbound / Outbound | dialplan `context` |
| 연결된 상담원 | channel / bridge / queue 이벤트 조합 |
| 현재 통화 상태 | 이벤트 기반 상태 머신 |

따라서 CTI 서버에는 별도의 Call State Machine 계층을 둔다.

    AMI Events
        ↓
    Channel State
        ↓
    Call State Machine
        ↓
    CTI Domain Event
        ↓
    WebSocket / DB / UI

이 복잡성이 AMI를 선택한 대가다.

---

## Revisit when

다음 요구사항이 생기면 ARI 기반 ACD를 다시 검토한다.

- `app_queue`로 표현하기 어려운 스킬 기반 라우팅
- 상담원 선택 알고리즘을 애플리케이션에서 완전히 제어해야 하는 경우
- 실시간으로 동적으로 변경되는 복잡한 분배 정책
- Asterisk queue보다 애플리케이션이 통화 lifecycle을 소유해야 하는 경우

그전까지는 검증된 `app_queue`를 사용하고
CTI 서버는 통화 orchestration이 아니라 관찰과 제어에 집중한다.