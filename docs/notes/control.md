# 통화 제어 (`control` 모듈)

Date: 2026-08-19

## Goal

`control` 모듈은 CTI가 Asterisk에 통화 명령을 보내는 창구다.
지금 있는 것은 클릭투콜, 받기, 끊기다.
클릭투콜 설계는 [ADR-0009](../adr/0009-outbound-click-to-call.md),
실측 근거는 [아웃바운드 콜 이벤트](../domain/outbound-call-events.md)에 있다.
받기·끊기 설계는 [ADR-0011](../adr/0011-answer-hangup.md)에 있다.

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

## 받기·끊기

    POST /api/v1/calls/{callId}/answer {loginId}   → 202
    POST /api/v1/calls/{callId}/hangup {loginId}   → 202

    CallControlService     본인 확인 → 대상 채널 결정
         ↓
    AmiChannelActions      받기: PJSIPNotify(울리는 채널, Event=talk)
                           끊기: Hangup(상담원 채널)          (ami 모듈)

- 받기는 벨이 울리는 상담원 본인만 (`ringingAgent` 대조),
  끊기는 CONNECTED 통화의 상담원 본인만 (`agent` 대조).
- 대상 채널은 `Call`이 기억한다. 울리는 채널은 `AgentCalled`의 DestChannel,
  상담원 채널은 `AgentConnect`의 DestChannel (아웃바운드는 첫 레그).
- 202는 명령이 나갔다는 뜻이다. 결과는 AMI 이벤트가 콜 상태와 푸시로 알린다.
- 전제 조건: 서버 `res_pjsip_notify`(+`pjsip_notify.conf`),
  단말 405HD `voip/talk_event/enabled=1`. 자세한 건 ADR-0011.

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
- 발신 중 취소(내가 걸었는데 끊고 싶다). 끊기가 CONNECTED만 허용해서 아직 없다.
  벨 울리는 콜의 거절과 함께 큐 재분배 실측 후 다룬다 (ADR-0011).

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

### 받기·끊기 (2026-08-19)

상담원 단말은 405HD(내선 1000), 고객 콜은 AMI Originate(PJSIP/1234)로 만들었다.

| 시나리오 | 기대 | 결과 |
|---|---|---|
| 벨 울리는 중 [받기] | 202, 405HD 자동응답, ANSWERED 푸시, ON_CALL | 통과 |
| 통화 중 [끊기] | 202, 상담원 레그만 Hangup, 고객 레그는 Asterisk가 정리, ENDED, ACW | 통과 |
| 벨 울리는 중 끊기 | 409 (CONNECTED 아님) | 통과 |
| 종료된 콜 받기 / 미로그인 상담원 끊기 | 404 | 통과 |

검증하며 안 것:

- **Local 채널 originate로는 추적 콜을 못 만든다.**
  `channel originate Local/0212345678@from-trunk ...`은 UserEvent가
  Local의 두 번째 반쪽(;2)에서 나와 "첫 채널만 통화 생성" 가드에 걸린다.
  테스트 콜은 실단말 채널(PJSIP/1234)을 Originate해서 만들어야 한다.
