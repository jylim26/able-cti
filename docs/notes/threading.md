# 스레드 모델

Date: 2026-08-18

## Goal

이 앱에 어떤 스레드가 있고, 각 스레드에서 무엇을 해도 되는가.

콜-상담원 연동(ADR-0006)부터 세션 하나를 여러 스레드가 건드리게 됐다.
새 기능이 이벤트나 명령을 다룰 때 이 문서의 기준을 따른다.

---

## 스레드는 세 종류다

| 스레드 | 개수 | 하는 일 |
|---|---|---|
| AMI 리더 | 1 | asterisk-java가 AMI 소켓을 읽는 스레드. 모든 AMI 이벤트 처리가 여기서 돈다 |
| 리스너 executor | 1 | `AgentCallEventListener`가 소유. 콜 종료 후 ACW 진입(AMI 명령 포함) 처리 |
| Tomcat 요청 풀 | 여러 개 | REST 요청 처리 (login, pause, unpause, logout) |

Spring 이벤트는 동기다. `publishEvent`는 리스너를 그 자리에서 직접
부르는 것과 같아서, 이벤트 리스너는 발행자의 스레드(AMI 리더)에서 돈다.
스레드를 바꾸고 싶으면 리스너가 직접 넘겨야 한다 ([ADR-0006](../adr/0006-spring-events-between-modules.md)).

---

## 규칙 하나: AMI 리더 스레드에서 sendAction을 부르지 않는다

> 왜 리더 스레드만 금지인가?

`sendAction`은 명령을 보내고 응답을 기다린다. 그 응답을 소켓에서
읽어주는 것이 AMI 리더 스레드다. 리더 스레드 위에서 응답을 기다리면
자기가 읽어야 할 응답을 자기가 기다린다. 타임아웃(5초)까지 멈추고,
그동안 AMI 이벤트 수신 전체가 선다.

다른 스레드는 해당 없다. Tomcat 스레드가 `sendAction`으로 5초를
기다려도 리더 스레드는 계속 응답을 읽어준다. HTTP 클라이언트는
어차피 응답을 기다리는 중이라 요청 스레드가 묶이는 것도 정상이다.

## 스레드 배정 기준은 무게가 아니라 입구다

"가벼운 일은 리더 스레드, 무거운 일은 딴 스레드"가 아니다.
기준은 위 규칙 하나에서 나온다.

| 작업 | 무게 | 스레드 | 왜 |
|---|---|---|---|
| 콜 연결 → 세션 ON_CALL | 가벼움 (메모리만) | AMI 리더 | 명령이 없어서 리더 위에서 바로 해도 된다 |
| 콜 종료 → ACW + `QueuePause` | 무거움 (`sendAction`) | executor | 리더 위에서 못 하니 넘긴다 |
| REST pause/unpause | 무거움 (`sendAction`) | Tomcat | 리더가 아니라서 기다려도 된다 |

    리더 스레드:      sendAction 금지 → 메모리 작업만
    Tomcat/executor:  sendAction 허용 → 기다리는 일 가능

새 기능을 만들 때: 이벤트를 받아 AMI 명령을 보내야 하면 스레드를
넘긴다. 메모리만 만지면 그 자리에서 한다.

---

## 단일 스레드가 주는 것: 순서

AMI 리더가 하나라서 이벤트 처리는 도착 순서대로다.
executor가 하나라서 콜 종료 처리도 던진 순서대로다.
수천 건이 몰리면 executor 큐에 쌓여 하나씩 소화된다.
작업당 최악 5초(sendAction 타임아웃)지만, 통화 종료 빈도는
상담원 수에 묶여 있어 밀릴 규모가 아니다. 밀리면 그때 푼다.

## 여러 스레드가 만나는 곳: 세션과 콜

세 스레드가 전부 `AgentService`로 들어온다. 같은 세션에
동시에 도달할 수 있다.

    executor:  통화 종료 → session.queueInboundCallEnded()
    Tomcat:    같은 순간 이석 버튼 → session.pause("lunch")

이 경합을 막는 것이 `AgentSession` 메서드의 `synchronized`다.
`Call`도 같다 (지금은 리더 스레드뿐이지만 보류·전환이 붙으면
Tomcat 스레드가 들어온다). 레지스트리 두 개는 `ConcurrentHashMap`이다.

| 지점 | 보호 |
|---|---|
| `AgentSession`, `Call` 메서드 | `synchronized` — 객체당 한 번에 한 스레드 |
| `AgentSessionRegistry`, `CallRegistry` | `ConcurrentHashMap` |
