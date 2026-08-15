# ADR-0002: 통화 식별자는 Asterisk의 linkedid를 그대로 쓴다

Status: Accepted  
Date: 2026-08-15

## Context

AMI 이벤트는 통화가 아니라 채널 단위로 온다 ([ADR-0001](0001-ami-over-ari.md)).
채널들을 "한 건의 통화"로 묶는 키는 `linkedid`다.

- 같은 통화에 속한 채널들은 같은 `linkedid`를 갖는다.
- 그 값은 통화에서 처음 만들어진 채널의 `uniqueid`와 같다.

여기서 질문이 하나 생긴다.

> 화면과 API에 보여줄 "통화 번호"로 무엇을 쓸 것인가?

선택지는 두 가지다.

| | linkedid 그대로 | CTI가 만든 별도 ID (UUID) |
|---|---|---|
| 값의 출처 | Asterisk | CTI 서버 |
| 관리할 매핑 | 없음 | callId ↔ linkedid 짝을 전 구간에서 유지 |
| Asterisk 교체 시 | 외부 API도 영향받음 | 외부 API는 그대로 |

---

## Decision

다음과 같이 결정한다.

- 통화(Call)의 식별자는 `linkedid`다.
- 외부 API에는 `callId`라는 이름으로 노출한다. 값은 linkedid 그대로다.
- 클라이언트는 이 값의 생김새를 해석하거나 직접 만들지 않는다.
  서버가 준 값을 그대로 돌려주는 용도로만 쓴다.
- CDR, queue_log, 녹취 파일명도 별도 변환 없이 같은 값으로 잇는다.

---

## Why

별도 UUID를 만들면 시스템의 모든 구간에서 두 값의 짝을 기억해야 한다.

    클라이언트 ── callId(UUID) ── CTI 서버 ── linkedid ── Asterisk
                                     │
                              여기서 항상 변환

통화 조회, DB 저장, 녹취 연결, 장애 추적까지 매번 변환이 끼어든다.

반면 linkedid를 그대로 쓰면 하나의 값이 전 구간을 관통한다.

    API 요청     callId   = 1755000000.100
    AMI 로그     Linkedid = 1755000000.100
    CDR          linkedid = 1755000000.100
    queue_log    callid   = 1755000000.100
    녹취 파일    1755000000.100.wav

문제가 생기면 값 하나로 모든 기록을 검색할 수 있다.

별도 UUID의 장점은 외부 API가 Asterisk에 묶이지 않는다는 것이다.
하지만 이 시스템은 Asterisk 한 대를 전제하고, PBX를 바꿀 계획도 없다.
쓰지 않을 독립성을 위해 매핑 관리 비용을 내지 않는다.

---

## Consequences

- 외부 API의 callId 값이 Asterisk가 만드는 형식에 묶인다.
  다만 클라이언트가 값을 해석하지 않기로 했으므로,
  묶이는 것은 "값이 어디서 왔는가"뿐이고 형식이 바뀌어도 클라이언트는 깨지지 않는다.
- 아웃바운드 콜을 만들 때는 문제가 하나 생긴다.
  전화를 걸기 전에 클라이언트에게 callId를 돌려줘야 하는데,
  linkedid는 채널이 만들어져야 생긴다.
  채널 ID를 CTI가 미리 정해서 linkedid를 예약하는 방법이 후보이며,
  아웃바운드를 설계할 때 별도 ADR로 다룬다.

---

## Revisit when

다음 요구가 생기면 CTI가 만드는 별도 식별자를 다시 검토한다.

- Asterisk가 아닌 PBX로 바꾸거나 같이 운영해야 하는 경우
- 여러 PBX에 걸친 통화를 하나의 통화로 합쳐서 보여줘야 하는 경우
