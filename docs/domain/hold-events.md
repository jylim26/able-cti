# 보류/해제에서 실제로 오는 AMI 이벤트와 함정

Date: 2026-08-20

보류/해제 설계의 입력. 코드를 쓰기 전에 실측했다.
시나리오: 큐 인바운드 연결 → 보류 8초 → 해제 → 상담원 끊기.
상담원 단말은 405HD, 고객은 PJSIP/1234 Originate.

## 방식

Asterisk에 "보류시켜라" 명령이 없다. 협의 전환(attended transfer)을 빌린다.

    보류 = SetVar(TRANSFER_EXTEN=내선) + Atxfer(상담원 채널 → hold context)
    해제 = CancelAtxfer(상담원 채널)

Atxfer를 걸면 고객은 자동으로 대기음과 함께 원래 bridge에 남고,
상담원은 "전환 대상"과 새 bridge에 묶인다. 전환 대상을 대기음만 트는
[hold] context로 주면 그게 보류다. 해제는 전환 취소 — 상담원이 원래 bridge로 복귀한다.

## 전제 조건 (전부 실측에서 걸렸다)

| 조건 | 없으면 |
|---|---|
| features.conf `[featuremap] atxfer => *2` | Atxfer 액션이 조용히 무시됨. AMI Atxfer는 명령이 아니라 이 DTMF를 채널에 주입하는 방식이다 |
| features.conf `[general] atxferabort/complete/threeway` | `[featuremap]`에 넣으면 features reload 실패. 소속 섹션이 다르다 |
| dialplan `Queue(queue01,t)` | 상담원 채널에 transfer feature가 없어 주입된 DTMF가 무시됨 |
| `TRANSFER_EXTEN` SetVar를 Atxfer 직전마다 | 이 채널 변수가 Exten 헤더보다 우선한다. 안 덮으면 이전 값으로 전환됨 (아래 함정 절) |
| musiconhold.conf default 클래스 | MOH 시작이 조용히 실패 |
| [hold] context | 전환 대상. MusicOnHold(default,300) 후 UserEvent(HoldGuardTimeout) — CTI가 죽어도 고아 보류를 스스로 정리하는 가드 |

## 보류 이벤트 순서 (실측 2026-08-20)

    DTMFEnd(상담원, *2 주입 흔적)
    Hold(상담원 채널)                      ← 상담원 단말이 보낸 게 아니라 Atxfer가 만든 것
    MusicOnHoldStart(고객 채널)            ← 고객 대기음은 Atxfer가 자동으로 튼다
    Newchannel(Local/1000@hold;1)          ← linkedid는 원래 통화 것
    Newchannel(Local/1000@hold;2)
    BridgeCreate(새 bridge B)
    BridgeEnter(Local;1 → B)
    DialEnd(→ Local;1, ANSWER)
    BridgeLeave(상담원 ← 원래 bridge A)
    BridgeEnter(상담원 → B)
    Newexten(Local;2, MusicOnHold)         ← 이 MOH는 상담원 쪽 (파이프 건너편)
    MusicOnHoldStart(Local;2)

### 보류 중 topology

    bridge A: [고객]            혼자, MOH 청취
    bridge B: [상담원, Local;1]
    (bridge 없음) Local;2       MusicOnHold(default,300) 실행 중

- 원래 두 PJSIP 채널은 계속 살아 있다. 죽는 채널 없음.
- **MOH가 두 곳이다.** 고객 것은 Atxfer의 자동 hold indication,
  Local;2 것은 [hold] context가 상담원에게 트는 것.
- **Local 레그 2개가 원래 linkedid를 달고 온다.** 지금 콜 조립이 그대로
  레그로 흡수하고, 해제 시 레그 종료로 정리된다. 마지막 Hangup 판정도 안 깨진다
  (스파이크 중 서버가 이 통화를 정상 추적·종료했다).

## 해제 이벤트 순서 (실측 2026-08-20)

    BridgeLeave(상담원 ← B)
    BridgeEnter(상담원 → A)                ← 원래 bridge ID로 복귀. bridge A는 재사용된다
    MusicOnHoldStop(고객)
    Unhold(상담원 채널)
    BridgeLeave(Local;1 ← B)
    BridgeDestroy(B)
    Hangup(Local;1, cause 16)
    Hangup(Local;2, cause 16)

### 해제 후 topology

    bridge A: [고객, 상담원]    보류 전과 같은 bridge ID

## 판정에 쓸 수 있는 사실

| 판정 | 근거 |
|---|---|
| 보류 확정 | 상담원이 고객 bridge에서 나가고(BridgeLeave A) Local;1과 새 bridge에 들어감. 고객 MusicOnHoldStart가 곁들여짐 |
| 해제 확정 | 상담원이 고객과 같은 bridge로 복귀(BridgeEnter A) + hold bridge 소멸 |
| 단일 이벤트 확정 금지 | Hold/Unhold·MOH 이벤트는 순서 보조 신호. 확정은 bridge 구성으로 |

원래 bridge ID(A)가 보류 전후로 유지되므로 "고객이 있는 bridge"를 기억하면
복귀 판정이 단순해진다. 단, ID 유지가 보장 스펙인지는 확인 안 됐다 —
"고객과 상담원이 같은 bridge"로 판정하는 쪽이 안전하다.

## 함정

- **CancelAtxferAction이 asterisk-java(3.41)에 없다.** 액션 이름 `CancelAtxfer`,
  파라미터 Channel 하나. 커스텀 액션 클래스를 정의해야 한다 (AtxferAction은 있다).
- **보류 중 DTMF 이벤트가 계속 흐른다.** 상담원·Local 채널에서 DTMFBegin/End가
  주기적으로 반복 관측됐다 (MOH 음원의 인밴드 오인식 추정). DTMF 이벤트를
  상태 판정에 쓰면 안 된다.
- **Atxfer 응답 Success는 "DTMF 주입 성공"일 뿐이다.** 202 받기(ADR-0011)와 같은
  구도 — 결과는 bridge 이벤트로 확인한다.
- 보류와 협의 전환은 같은 attended transfer 자원이라 동시에 못 쓴다.
  보류 중 전환 요청은 거절해야 한다.
- **`TRANSFER_EXTEN`은 빠르지만 낡은 값이 사고를 낸다.**
  - 왜 쓰나: Atxfer의 Exten 헤더는 DTMF 큐잉(한 자리씩 입력 시뮬레이션)이라
    느리다. 변수를 심으면 digit 수집을 건너뛰어 즉시 확정된다.
  - 함정: 변수는 채널에 남는다. 한 번 보류한 콜에서 나중에 협의 전환을 하면
    Exten 헤더의 대상 번호 대신 낡은 변수 값(상담원 자신)으로 다이얼한다.
  - 규칙: **모든 Atxfer 직전에 반드시 SetVar로 새로 덮어쓴다.** 삭제 말고 덮어쓰기 —
    속도를 유지하면서 낡은 값이 읽힐 틈을 없앤다.
  - 남는 한계: 전화기에서 수동 `*2` 전환 시 CTI가 남긴 변수가 읽혀
    digit 입력 없이 그 번호로 전환된다. CTI 버튼 조작이 기본이면 수용 가능.

## 스파이크 재현 방법

고객 콜: `Action: Originate, Channel: PJSIP/1234, Exten: 0212345678, Context: from-trunk`.
Local 채널 originate는 UserEvent가 ;2 반쪽에서 나와 추적이 안 된다
([통화 제어 노트](../notes/control.md)의 검증 절).
보류: `SetVar(TRANSFER_EXTEN)` 후 `Atxfer(Exten: 1000, Context: hold)`.
해제: `CancelAtxfer(Channel: 상담원 채널)`.
