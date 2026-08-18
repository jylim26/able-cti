# 통화 제어 (`control` 모듈)

Date: 2026-08-19

## Goal

`control` 모듈은 CTI가 Asterisk에 통화 명령을 보내는 창구다.
지금 있는 것은 클릭투콜 하나다. 설계는 [ADR-0009](../adr/0009-outbound-click-to-call.md),
실측 근거는 [아웃바운드 콜 이벤트](../domain/outbound-call-events.md)에 있다.

---

## Structure

    POST /api/v1/calls {loginId, number}
         ↓
    CallControlService          허용 검사 → pending 등록 → Originate 전송 → callId 응답
         │                      (검사: 세션 존재 + PAUSED(사유=OUTBOUND))
         ↓
    AmiOriginateActions         Originate 액션 전송 (ami 모듈)
         ↓
    PendingOutboundRegistry     예약 채널 ID → 발신 정보 메모 (call 모듈)

이후는 이벤트가 이어받는다. 번역기(`AmiCallEventTranslator`)가

- 상담원 채널 `Newchannel`에서 pending을 꺼내 OUTBOUND 통화를 만들고
- `DialEnd(ANSWER)`에서 CONNECTED로 전이하고 (`CallConnectedEvent` 발행)
- 채널이 생기기 전 실패는 `OriginateResponse(Failure)`에서
  `OutboundCallFailedEvent`로 알린다. push 모듈이 상담원 토픽으로 내보낸다.

상담원 상태는 기존 배선을 그대로 탄다. 연결에서 ON_CALL,
종료에서 통화 전 상태(PAUSED, 사유=OUTBOUND)로 복귀한다.
아웃바운드 종료는 ACW에 들어가지 않는다 (`AgentCallEventListener`가 방향으로 분기).

## 설정

    cti:
      outbound:
        context: from-internal    # Originate가 상담원 응답 후 진입할 dialplan context
        ring-timeout-ms: 15000    # 상담원 무응답 타임아웃

개발환경 고객 역할은 내선 1234라 from-internal의 `_1XXX` Dial로 충분하다.
트렁크가 생기면 아웃바운드 전용 context를 만들고 이 값을 바꾼다.

## 아직 없는 것

- 자동응답 헤더. 개발은 단말(MicroSIP) 설정이나 명령행 응답으로 검증했다.
  서버가 `Call-Info` 헤더로 요청하는 방식은 지원 단말이 생길 때 붙인다.
- 발신 중 취소(내가 걸었는데 끊고 싶다). 받기·끊기 단계에서 함께 다룬다.

---

## 실통화 검증 결과

Date: 2026-08-19

| 시나리오 | 기대 | 결과 |
|---|---|---|
| LOGIN 사유 상태에서 발신 | 409 거부 | 통과 |
| OUTBOUND 이석 후 발신, 양쪽 응답 | callId 즉시 응답, RINGING→CONNECTED→ENDED(answered=true), 상담원 ON_CALL→PAUSED(OUTBOUND) 복귀 | 통과 |
| 상담원 무응답 (15초) | ENDED(answered=false), 상담원 PAUSED(OUTBOUND) 유지 | 통과 |
| 인바운드 회귀 (1234→대표번호→큐→응답) | 기존 흐름 그대로, ACW 진입 | 통과 |

전 시나리오에서 `ignored AMI event` 경고 0건.
인바운드 큐 콜의 DialEnd(ANSWER)는 방향 필터에서 조용히 걸러졌다.

단말 미등록 실패(`OutboundCallFailedEvent` 푸시)는 개발환경 내선이
전부 등록돼 있어 실통화로는 못 봤다. 단위 테스트와
[실측](../domain/outbound-call-events.md)의 단말 미등록 시나리오로 확인했다.
