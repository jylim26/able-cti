# ADR-0003: 추적할 콜은 dialplan이 직접 알려준다

Status: Accepted  
Date: 2026-08-15

## Context

CTI가 모든 콜을 추적하지는 않는다.

- 내선끼리의 통화는 사내 전화 영역이라 컨택센터 CTI가 추적하지 않는 것이 업계 관행이다.
- 에코 테스트(600) 같은 점검용 콜도 기록에 남으면 노이즈다.

그래서 질문이 생긴다.

> "이 콜을 추적해야 하는가"를 CTI는 무엇을 보고 판단하는가?

후보는 네 가지다.

### 후보 1. 채널 변수를 이벤트에 실어 보내기

dialplan이 `Set(CTI_TRACKED=1)`로 표시를 심고, `manager.conf`의 `channelvars`
설정으로 모든 채널 이벤트에 그 변수를 실어 보내는 방식.

두 가지 이유로 기각한다.

- 우리가 쓰는 라이브러리(asterisk-java 3.41)가 이 값을 읽지 못한다.
  이벤트에 실려 와도 자바 객체로 옮겨주는 코드가 없어서 그냥 버려진다.
  이벤트 클래스 전체를 javap로 뒤져 확인했다.
- `Newchannel` 이벤트는 dialplan이 실행되기 전에 발생한다.
  즉 첫 이벤트에는 변수가 아직 없다. 콜 시작을 알아채는 시점이 어차피 늦어진다.

### 후보 2. 번호 패턴

착신 번호가 내선처럼 생겼으면(`_1XXX`) 추적하지 않는 방식.

번호의 생김새로 추측하는 것이다. 내선 번호 규칙이 바뀌면
아무 에러 없이 조용히 잘못 분류된다. 기각한다.

### 후보 3. context 보고 판단하기

`Newchannel`의 context가 CTI 설정에 적어둔 목록(`from-trunk` 등)에 있으면
추적하는 방식.

콜이 실제로 어느 문으로 들어왔는지를 보는 것이라 후보 1, 2보다 낫다.
하지만 같은 context 이름을 dialplan과 CTI 설정 두 곳이 알고 있어야 한다.
인입 경로가 늘어날 때(통신사 이중화 등) 한쪽만 고치면
그 콜은 에러 없이 조용히 사라진다.

### 후보 4. dialplan이 직접 알려주기 (선택)

추적할 콜이 지나가는 자리에서 dialplan이 CTI에게 직접 알린다.

---

## Decision

추적할 콜은 dialplan이 `UserEvent`로 알려준다.

    exten => 0212345678,1,Answer()
     same => n,UserEvent(CtiCallStarted,Direction: INBOUND)
     same => n,Queue(queue01)

- CTI는 `CtiCallStarted` 이벤트를 받았을 때만 통화를 만든다.
  인바운드 통화가 생기는 입구는 이것 하나뿐이다.
- 표시가 없는 콜(내선 통화, 에코 테스트)은 자동으로 추적에서 빠진다.
  기본이 "추적 안 함"이고, 추적할 콜만 dialplan에서 골라 켜는 구조다.

### UserEvent에 실을 값

Asterisk는 UserEvent를 보낼 때 그 채널의 정보(Channel, Uniqueid, Linkedid,
CallerIDNum, Exten 등)를 자동으로 붙여준다.

> 자동으로 붙는 값은 직접 실지 않는다.

예를 들어 `Linkedid`를 직접 실으면 자동으로 붙는 헤더와 이름이 겹치고,
라이브러리가 두 값을 한 줄로 이어붙여서 값이 깨진다.
직접 실는 것은 자동으로 붙지 않는 값(Direction)뿐이다.

---

## Why

후보 3(context)과 비교하면 차이가 분명하다.

| 기준 | context 보고 판단 | dialplan이 직접 알림 |
|---|---|---|
| "추적하라"를 아는 곳 | dialplan + CTI 설정 두 곳 | dialplan 한 곳 |
| 인입 경로가 늘어나면 | 양쪽을 맞춰야 함. 어긋나면 콜이 조용히 사라짐 | dialplan만 고치면 됨 |
| CTI가 알아야 하는 것 | context 이름 목록 | 없음. 이벤트만 받으면 됨 |
| 방향·유형이 늘어나면 | context를 더 만들어야 함 | UserEvent에 실는 값만 추가 |

콜을 어디로 보낼지 정하는 곳은 dialplan이다.
그렇다면 추적 여부도 같은 곳에서 정하는 것이 맞다.
정하는 곳과 알리는 곳이 같으면 서로 어긋날 일이 없다.

---

## Consequences

- dialplan 규율이 필요하다. 추적해야 할 인입 경로에 UserEvent를 빠뜨리면
  그 콜은 CTI에 보이지 않는다. 다만 context 방식도 설정을 빠뜨리면
  똑같은 일이 생기므로, 새로 생긴 위험은 아니다.
- `manager.conf`의 read 권한에 user 이벤트가 포함되어야 한다. 현재는 `read = all`.
- 이벤트 순서에 특징이 하나 생긴다.
  루트 채널의 `Newchannel`이 먼저 오고 `CtiCallStarted`가 그 뒤에 온다.
  CTI는 앞의 것을 버리고 뒤의 것으로 통화를 만든다.
  UserEvent에 같은 채널 정보가 자동으로 붙어 있어서 버려도 잃는 것이 없다.
  이 순서는 뒤집히지 않는다. 채널이 만들어진 다음에야 dialplan이 실행되기 때문이다.
- UserEvent는 콜이 시작할 때 한 번만 온다.
  그래서 CTI가 재시작한 뒤 "지금 진행 중인 이 콜은 추적 대상이었나"를 물어볼 수단이 없다.
  재동기화를 만들 때는 dialplan에 상속 채널 변수(`Set(__CTI_TRACKED=1)`)를 함께 심고
  `GetVar`로 물어보는 방식을 쓴다. 그 변수는 재동기화 기능의 일부이므로
  그 설계에서 함께 추가한다.

---

## Revisit when

- asterisk-java가 채널 변수(ChanVariable)를 읽을 수 있게 되면 후보 1을 다시 본다.
  다만 Newchannel 타이밍 문제는 그대로 남으므로,
  UserEvent를 대체하기보다 표시가 빠진 콜을 찾아내는 보조 수단으로 검토한다.
