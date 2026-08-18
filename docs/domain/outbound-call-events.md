# 아웃바운드(Originate) 콜에서 실제로 오는 AMI 이벤트

Date: 2026-08-18

아웃바운드 설계의 입력이다. 추측하지 않고 실측했다.
측정 방법: AMI에 직접 접속해 `Originate` 액션을 보내고 모든 이벤트를 기록했다.

    Action: Originate
    Channel: PJSIP/1000          ← 상담원 단말을 먼저 부른다
    Context: from-internal
    Exten: 1234                  ← 상담원이 받으면 이 번호로 Dial
    ChannelId: cti-spike-b2      ← CTI가 미리 정한 채널 ID
    Async: true

시나리오 4개를 측정했다.

| 시나리오 | 상담원 | 고객 |
|---|---|---|
| 정상 발신 | 받음 | 받음 |
| 고객 무응답 | 받음 | 안 받음 (20초) |
| 상담원 무응답 | 안 받음 (15초) | 호출 안 됨 |
| 단말 미등록 | 채널 생성 실패 | 호출 안 됨 |

---

## 1. 정상 발신

시각은 Originate 전송 기준 실측값이다. 채널은 "받아야" 생기는 것이 아니라
**부르기 시작하는 순간** 생긴다. 그래서 Newchannel이 벨보다 먼저다.

    +0.00  Originate 전송
           (응답)            Originate successfully queued   ← Async라 접수 확인일 뿐
    +0.03  Newchannel        상담원 채널 (상태 Down)         ← 아직 벨도 안 울림.
                                                               Uniqueid=Linkedid=예약한 ChannelId.
                                                               Exten은 s, CallerIDNum은 내선(1000)
             └ CTI: pending 조회 → 통화 생성 (RINGING)
           NewCallerid                                       ← Originate의 CallerID가 이제 적용됨
           DialBegin                                         ← Dest만 있고 소스 채널 없음 (함정 1)
    +0.07  Newstate          Ringing                         ← 상담원 단말이 울리기 시작
    ── 상담원이 받음 (자동응답) ──
    +3.3   Newstate          Up
           DialEnd           dialStatus=ANSWER               ← 상담원 레그의 것. 소스 헤더 없음 (함정 1)
           OriginateResponse Success reason=4                ← 성공에도 온다 (함정 2)
           Newexten                                          ← 이제야 dialplan 실행. Exten이 s→1234
             └ dialplan이 고객에게 Dial
           DialBegin                                         ← 소스=상담원 채널, Dest=고객 채널
    +3.4   Newchannel        고객 채널                       ← Uniqueid는 Asterisk 채번(dev-...),
                                                               Linkedid는 예약값 그대로
           Newstate          Ringing  고객 채널
    ── 고객이 받음 ──
    +7.8   DialEnd           dialStatus=ANSWER               ← 소스 Linkedid 있음. 고객 응답의 근거
             └ CTI: CONNECTED 전이
           BridgeCreate
           BridgeEnter × 2
    ── 끊음 ──
    +15.8  BridgeLeave × 2
           Hangup            고객 채널    cause=16
           Hangup            상담원 채널  cause=16

핵심 관찰 세 가지.

- **ChannelId 예약이 linkedid가 된다.** 첫 채널의 Uniqueid로 그대로 들어가고
  (`dev-` 같은 systemname 접두사도 안 붙는다), 고객 채널의 Linkedid로 전파된다.
  전화를 걸기 전에 callId를 알 수 있다 ([ADR-0002](../adr/0002-linkedid-as-call-id.md)의 숙제).
- **dialplan은 상담원이 받은 뒤에 실행된다.** 받기 전까지 Exten은 `s`다.
  벨이 울리는 동안 고객번호(1234)는 어떤 이벤트에도 실리지 않는다.
  고객번호를 알려면 CTI가 따로 기억해야 한다.
- **큐 콜과 이벤트 어휘가 다르다.** AgentConnect가 없다. 고객 응답은 DialEnd가 알려준다.

---

## 2. 고객 무응답 — 상담원이 받은 뒤 고객이 안 받음

고객 벨은 dialplan Dial의 타임아웃(20초)까지 울렸다.

    ... 상담원 응답, 고객 호출까지 정상과 동일 ...
    DialEnd           dialStatus=NOANSWER             ← 소스 Linkedid 있음
    Hangup            고객 채널    cause=0 Unknown    ← 16이 아니다 (함정 4)
    Hangup            상담원 채널  cause=16

DialEnd의 DialStatus가 ANSWER가 아니면 무시하면 된다.
통화는 마지막 Hangup에서 응답 없이(ENDED, answered=false) 끝난다.
포기호와 같은 판정식이 그대로 동작한다.

## 3. 상담원 무응답

Originate의 Timeout(15초) 동안 상담원이 안 받았다.

    Newchannel · DialBegin · Newstate Ringing         ← 정상과 동일
    ── 15초 무응답 ──
    OriginateResponse Failure reason=3                ← Uniqueid에 예약 ChannelId가 실려 온다
    Hangup            상담원 채널  cause=19 User alerting, no answer

## 4. 단말 미등록

등록되지 않은 내선(PJSIP/1002)으로 Originate했다.

    OriginateResponse Failure reason=0                ← Newchannel 없이 이것만 온다.
                                                        Uniqueid에 예약값이 실려 온다

채널이 만들어진 적 없으므로 Hangup도 없다.
실패를 알 수 있는 유일한 신호가 OriginateResponse다.

### OriginateResponse의 Reason 값 (실측)

| Reason | 의미 |
|---|---|
| 0 | 채널 생성 실패 (미등록 등) |
| 3 | 호출 타임아웃 (상담원 무응답) |
| 4 | 성공 |

---

## 함정

### 함정 1. DialEnd(ANSWER)는 두 번 온다. 하나는 가짜다

상담원이 받을 때도 DialEnd ANSWER가 온다. Originate 자체가 내부적으로
Dial이기 때문이다. 이걸 고객 응답으로 오인하면 상담원이 받는 순간
CONNECTED가 돼버린다.

둘을 가르는 것은 소스 채널 헤더다.

| | 상담원 레그 DialEnd | 고객 레그 DialEnd |
|---|---|---|
| Channel / Linkedid (소스) | **없음** | 있음 (상담원 채널) |
| DestChannel | 상담원 채널 | 고객 채널 |
| 온 시점 | 상담원 응답 | 고객 응답 |

asterisk-java의 `getLinkedId()`는 소스 Linkedid를 읽으므로 상담원 레그의
DialEnd에서는 null이다. "linkedid로 통화를 찾았을 때만 일한다"는
번역기의 기존 규율이 이 가짜를 자동으로 거른다.
다만 우연에 기대지 않도록 이 사실을 알고 있어야 한다.

### 함정 2. OriginateResponse는 성공에도 온다

이름과 달리 실패 통지 전용이 아니다. 성공하면 Response: Success에
Reason 4로 온다. 실패 처리 리스너는 Response 필드가 Failure인 것만 봐야 한다.

### 함정 3. 벨 구간의 채널 정보는 반쯤 비어 있다

Newchannel 시점의 Exten은 `s`, CallerIDNum은 상담원 내선이다.
고객번호는 어디에도 없다. 이 통화가 "누구에게 거는 것인지"는
이벤트만으로 알 수 없고, Originate를 보낸 쪽이 기억해야 한다.

### 함정 4. 고객 무응답의 고객 채널 Hangup은 Cause 0이다

Cause 16(Normal Clearing)이 아니다. Cause 값으로 종료 사유를
분류하려 들면 여기서 어긋난다. 응답 여부는 Cause가 아니라
answeredAt 유무로 판정한다 ([ADR-0004](../adr/0004-call-state-machine.md)와 동일).

---

## 자동응답

클릭투콜은 상담원이 수화기를 손으로 들면 목적(화면으로 통화 제어)이
무너진다. 상담원 단말의 자동응답이 전제다.

- 자동응답은 단말 기능이다. 서버는 SIP 헤더(`Call-Info: answer-after=0`)로
  요청만 할 수 있고, 단말이 지원해야 동작한다. 단말 기종 확정 시
  자동응답 지원을 요구사항에 넣는다.
- 개발환경(MicroSIP)에서는 설정의 자동응답을 켜서 검증한다.
- Originate에 헤더를 실어 보내는 방식(`PJSIP_HEADER`)은 아직 실측하지 않았다.
  구현 단계에서 확인한다.

이번 실측은 MicroSIP의 명령행 응답(`microsip.exe /answer`)으로 진행했다.
