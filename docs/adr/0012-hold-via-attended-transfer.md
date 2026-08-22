# ADR-0012: 보류는 hold context로의 협의 전환, 확정은 hold 레그가 있는 bridge 구성으로

Status: Accepted
Date: 2026-08-20

## Context

로드맵 5, 보류/해제를 만든다. 실측은 끝났다
([보류 실측](../domain/hold-events.md)). 방식은 거기서 확정됐다.

    보류 = SetVar(TRANSFER_EXTEN=내선) + Atxfer(상담원 채널 → hold context)
    해제 = CancelAtxfer(상담원 채널)

Asterisk에 "보류시켜라" 명령이 없어서 협의 전환(attended transfer)을 빌린다.
전환 대상을 대기음만 트는 `[hold]` context로 주면 고객은 원래 bridge에 남아
대기음을 듣고, 상담원은 hold context가 만든 Local 채널과 새 bridge에 묶인다.

방식이 정해졌으니 남는 질문은 콜 모델이다.

> 보류를 콜 모델에서 어떻게 표현하고, 무엇으로 확정하는가?

실측이 판정 재료를 준다. 보류 중 topology는 이렇다.

    bridge A: [고객]                혼자, 대기음 청취
    bridge B: [상담원, Local;1]     Local은 [hold] context가 만든 채널

그리고 실측의 경고: Hold/Unhold·MOH 같은 단일 이벤트로 확정하면 안 된다.
확정은 bridge 구성으로 한다. 이는 로드맵의 통화 제어 완료 판정 원칙이기도 하다.

## Decision

### 보류는 상태가 아니라 통화에 붙는 정보다

`CallState`는 4개 그대로 둔다. `Call`에 `held` 플래그를 더한다.
ADR-0004가 벨울림에 내린 결정과 같다 — 전이표는 안 바뀐다.

벨울림과 논거가 하나 다르다. 벨울림은 "사실이 안 바뀌어서"(고객은 여전히 큐 대기)
상태가 아니었다. 보류는 bridge 구성이 실제로 바뀐다. 그래도 상태로 만들지 않는
이유는 Why에 적었다.

### 레그가 자기 bridge를 기억한다

`CallLeg`에 `bridgeId`를 더한다. `BridgeEnter`가 채우고 `BridgeLeave`가 지운다.
콜 모델이 bridge를 아는 유일한 통로다. 별도 bridge 레지스트리는 만들지 않는다.

### 판정: 상담원 bridge에 살아 있는 hold 레그가 있는가

    held = 상담원 레그의 bridgeId 안에
           살아 있는 hold 레그가 같이 있다

hold 레그는 채널명으로 식별한다 — `[hold]` context가 만드는 Local 채널
(`Local/…@hold-…`)만 이 이름을 가진다. 레그 역할(role) 필드는 만들지 않는다.

판정은 bridge 이벤트가 올 때마다 다시 계산하는 순수 함수다.
플래그가 false→true로 바뀌면 보류 확정, true→false면 해제 확정이고
그 순간 이벤트를 발행한다. 누가 보류를 걸었는지 판정은 모른다 —
CTI 버튼이든 전화기 수동 `*2`든 가드 만료든 같은 코드가 흡수한다.

고객 레그는 판정에 안 쓴다. hold 레그는 `[hold]` context에서만 태어나므로
그것이 상담원과 한 bridge에 있다는 것만으로 모호함이 없다.
"상담원·고객이 다른 bridge"를 판정 기준으로 쓰지 않는 이유는 Why에 적었다.

### API: 받기·끊기와 같은 자리, 같은 구도

    POST /api/v1/calls/{callId}/hold    {loginId}
    POST /api/v1/calls/{callId}/unhold  {loginId}

- 이름은 상담원 API의 pause/unpause와 나란하다.
- 통화 중(CONNECTED)인 상담원 본인만. loginId → 내선 매핑(ADR-0007)으로 대조한다.
- hold는 `held=false`일 때만, unhold는 `held=true`일 때만 받는다.
- **보류 중 끊기는 거절한다.** 보류 중 상담원 레그를 죽이면 고객이
  가드 타임아웃(최대 300초)까지 대기음에 갇힌다. 해제 후 끊는다.
- 응답은 202 Accepted. ADR-0011과 같은 구도다 — Atxfer의 Success 응답은
  "DTMF 주입 성공"일 뿐이고(실측), 진짜 결과는 bridge 이벤트가 판정한다.

명령 전송 규칙 하나 (실측의 함정): **모든 Atxfer 직전에 `TRANSFER_EXTEN`을
SetVar로 새로 덮어쓴다.** 이 채널 변수는 채널에 남아서, 안 덮으면
낡은 값으로 전환된다.

### 푸시: held·resumed

콜 이벤트 봉투(ADR-0010)에 `held`, `resumed`를 더한다.
판정 플래그가 바뀌는 순간 상담원 토픽으로 나간다.

### 지금 하지 않는 것

- **진행 중 상태(HOLDING/RESUMING) 없음.** 버튼과 확정 푸시 사이 ~1초 공백,
  더블클릭 시 Atxfer 이중 주입 가능. 받기·끊기가 이미 수용한 틈과 같다.
- **명령 결과 추적 없음.** 202 돌려주고 잊는다. 판정이 안 바뀌면 화면도
  안 바뀌고, 다시 누르면 된다.
- **레그 역할(role) 없음.** 지금 필요한 식별은 상담원 레그(`agentChannel`,
  ADR-0011)와 hold 레그(채널명)뿐이다.
- **가드 만료(HoldGuardTimeout UserEvent) 처리 없음.** dialplan 가드는
  고아 보류를 스스로 정리하는 안전장치고, 만료되면 hold 레그가 죽어
  판정이 알아서 바뀐다. 만료 시나리오는 미실측 — 운영에서 문제 되면 실측한다.
- **보류 이력 영속화 없음.** 로드맵 7이다.

## Why

### 왜 상태가 아니라 플래그인가

전이표를 지키기 위해서다. 보류를 상태로 만들면 CONNECTED↔HELD가 더해지고,
다음 단계(전환·3자)에서 상태 조합이 계속 불어난다. 협의 전환만 해도
진행·완료 대기·중단이 있다. 그 상태들이 정말 필요한지는 전환 실측이
말해줄 일이고, 아직 없다. 지금 상태 체계를 키우면 추측 설계다.

플래그로 시작해도 버리는 것이 없다. 판정 로직(topology 함수)은 플래그든
상태든 그대로 쓰이고, 승격이 필요하면 ADR-0004처럼 새 ADR로 대체하면 된다.

### 왜 "다른 bridge"가 아니라 "hold 레그"로 판정하는가

협의 전환 때문이다. 협의 전환도 같은 Atxfer라서 topology가 보류와
같은 모양이 된다 — 고객 혼자 + 대기음, 상담원은 전환 대상과 새 bridge.

| 판정 기준 | 보류 | 협의 전환 중 |
|---|---|---|
| 상담원·고객이 다른 bridge | true | true — **오판** |
| 상담원 bridge에 hold 레그 | true | false — 전환 대상은 `@hold`가 아니다 |

bridge 분리로 판정하면 전환을 만드는 순간 보류로 오판한다.
hold 레그 기준은 지금 몇 줄 더 드는 대신 그 미래를 막는다.

### 왜 판정을 순수 함수로 하는가

보류를 거는 주체가 서버만이 아니어서다. 전화기에서 수동 `*2`를 눌러도,
가드가 만료돼도 bridge는 바뀐다. "내가 보낸 명령의 결과"를 추적하는 대신
"지금 bridge가 어떤 모양인가"만 보면, 어느 경로로 바뀌었든 플래그가 사실을
따라간다. 외부 조작 흡수를 위한 별도 코드가 없다.

## Consequences

- `CallState` 전이표는 그대로다. 콜 상태 머신 테스트도 안 바뀐다.
- 콜 모델이 bridge를 알게 된다. 로드맵 6(전환)의 완료 판정도
  이 `bridgeId` 추적 위에서 한다.
- 보류·협의 전환은 같은 attended transfer 자원이라 동시에 못 쓴다(실측).
  전환을 만들 때 `held` 게이트로 거절하고, 그 시점에 `held` 플래그를
  "atxfer 자원 상태" enum이나 상태로 승격할지 다시 결정한다.
- CancelAtxfer는 asterisk-java(3.41)에 없다. 커스텀 액션 클래스를 정의한다
  (액션 이름 `CancelAtxfer`, 파라미터 Channel 하나).
- 전화기 수동 `*2` 전환 시 CTI가 남긴 `TRANSFER_EXTEN`이 읽혀 digit 입력
  없이 그 번호로 전환된다(실측의 한계). CTI 버튼 조작이 기본이라 수용한다.
- hold 레그 2개(Local 반쪽들)가 원래 linkedid로 콜에 흡수된다. 실측에서
  마지막 Hangup 판정이 안 깨지는 것을 확인했다.

## Revisit when

- 호전환을 설계할 때. `held` 플래그의 enum/상태 승격, 레그 역할 도입,
  진행 중 상태(HOLDING 등)의 필요를 전환 실측을 입력으로 다시 본다.
- 더블클릭 이중 주입이 실제 사고를 낼 때. 요청 시각 기록으로 막는 것부터.
- 보류 시간 통계가 필요할 때 (로드맵 7). held 전환 시각의 이력 영속화.
