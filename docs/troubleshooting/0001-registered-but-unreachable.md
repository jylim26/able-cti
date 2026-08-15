# TS-0001: 등록은 되어 있는데 벨이 안 간다

Date: 2026-08-15

## 증상

내선 1000(Zoiper) → 1001(Linphone) 발신 시 1001이 울리지 않는다.
반대 방향 1001 → 1000은 정상이다.

두 내선 모두 `pjsip show contacts`에는 등록되어 있다.
등록되어 있다는 것과 벨을 보낼 수 있다는 것은 다르다는 게 이 문제의 핵심이다.

---

## 진단 순서

같은 증상이 또 나오면 이 순서로 본다.

### 1. contact가 등록되어 있는가

    docker compose exec asterisk asterisk -rx "pjsip show contacts"

    Contact:  1001/sip:1001@127.0.0.1:40977  bc3ff71f  NonQual  nan

등록은 있다. `NonQual`은 qualify(생사 확인)를 안 하고 있다는 뜻이라
이 표시만으로는 살아 있는 경로인지 알 수 없다.

### 2. 그 포트에 실제로 누가 듣고 있는가

contact의 포트에 소프트폰이 바인딩되어 있는지 개발 PC에서 확인한다.

    lsof -nP -iUDP:40977

아무것도 안 나왔다. **등록된 주소가 죽은 주소다.**

### 3. INVITE가 어디로 가서 어떻게 되는가

    docker compose exec asterisk asterisk -rx "pjsip set logger on"
    docker compose exec asterisk asterisk -rx "channel originate PJSIP/1001 application Echo"
    docker compose logs asterisk --since 2m

    INVITE sip:1001@127.0.0.1:40977 SIP/2.0   ← 같은 INVITE가 계속 반복
    INVITE sip:1001@127.0.0.1:40977 SIP/2.0      (응답이 없어 재전송)

죽은 주소로 보내니 응답이 없고, 타임아웃까지 재전송만 반복된다.
확인이 끝나면 로거를 끈다: `pjsip set logger off`

---

## 원인

### 소프트폰과 Asterisk 사이에 프록시가 있다

SIP 로그에서 이런 불일치가 보였다.

    Via: SIP/2.0/UDP 127.0.0.1:64572;rport=38225
                               ↑ 폰이 보낸 포트    ↑ Asterisk에 도착한 출발 포트

폰은 64572에서 보냈는데 Asterisk에는 38225에서 온 것으로 보인다.
잠시 뒤에는 44853으로 또 바뀌었다.

Docker Desktop(macOS·Windows)의 host 네트워크는 진짜 host 네트워크가 아니라
**UDP 프록시**를 거친다. 리눅스에서 도커를 네이티브로 돌리면 없는 구간이다.

    소프트폰 ──> [Docker Desktop 프록시] ──> Asterisk
      :64572        :38225 (임시 포트)         :5060

프록시의 포트 매핑은 트래픽이 없으면 사라진다. NAT와 같은 성질이다.

### 두 폰의 운명이 갈린 이유

`rewrite_contact=yes`라서 Asterisk는 등록 때 본 출발 주소(프록시 포트)를
벨 보낼 주소로 기억한다. 그 주소는 프록시 매핑이 살아 있을 때만 유효하다.

| | Zoiper (1000) | Linphone (1001) |
|---|---|---|
| 재등록 주기 | 짧다 | 길다 |
| 프록시 매핑 | 재등록 트래픽으로 계속 갱신 | 등록 후 침묵 → 매핑 소멸 |
| 결과 | 벨이 간다 | 등록만 남고 벨이 안 간다 |

폰 잘못이 아니다. **침묵하는 전화기는 도달 불가능해지는 환경**이었고,
그걸 막는 장치(qualify)가 꺼져 있었던 것이다.

---

## 조치

`ps_aors`의 `qualify_frequency`를 30초로 설정했다 (`V2__aor_qualify.sql`).

Asterisk가 30초마다 각 contact로 OPTIONS를 보낸다. 효과는 두 가지다.

1. **경로 유지** — 주기적 트래픽이 프록시/NAT 매핑을 계속 살려둔다
2. **생사 가시화** — `pjsip show contacts`의 상태가 `NonQual` 대신
   `Avail`/`Unavail`로 나와서, 죽은 등록을 눈으로 구분할 수 있다

qualify는 contact가 **새로 등록될 때** 적용된다.
이미 죽어 있는 등록에는 소용없으므로 소프트폰을 재등록시켜야 한다
(앱 재시작 또는 계정 off/on).

## 확인 방법

    docker compose exec asterisk asterisk -rx "pjsip show contacts"

모든 contact가 `Avail`이고 RTT가 찍히면 정상이다.
`Unavail`이 보이면 그 내선은 지금 벨을 받을 수 없는 상태다.
