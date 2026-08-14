# 1단계: AMI 연결

Step: 1  
Date: 2026-08-14

## Goal

애플리케이션이 기동할 때 Asterisk에 AMI로 로그인하고, 종료할 때 로그아웃한다.

이번 단계의 범위는 연결 자체까지다.

| 포함 | 제외 |
|---|---|
| AMI 접속 정보 외부화 | 이벤트 해석 |
| 연결 수립과 종료 | 액션 전송 |
| 이벤트 수신 확인 (로그) | 콜 상태 관리 |

AMI를 선택한 이유는 [ADR-0001](../adr/0001-ami-over-ari.md)에 정리했다.

---

## Structure

    application.yml (asterisk.ami.*)
         ↓
    AmiProperties            접속 정보를 담는 record
         ↓
    AmiConnectionConfig      ManagerConnection을 빈으로 등록
         ↓
    ManagerConnection        asterisk-java 객체 (아직 미접속)
         ↓
    AmiConnectionManager     로그인 / 로그아웃
         ↑
    List<ManagerEventListener>   현재 비어 있음

한 클래스로 구현할 수도 있으나, 세 클래스는 각각 다른 이유로 변경된다.

| 클래스 | 변경 시점 |
|---|---|
| `AmiProperties` | 접속 정보가 바뀔 때 (개발 VM ↔ 운영) |
| `AmiConnectionConfig` | 라이브러리를 교체할 때 |
| `AmiConnectionManager` | 기동 순서 문제가 발생할 때 |

---

## Components

### 1. AmiProperties

yml의 `asterisk.ami` 하위 값을 record 하나에 바인딩한다.

```java
@ConfigurationProperties(prefix = "asterisk.ami")
public record AmiProperties(String host, int port, String username, String password) {}
```

대응하는 설정은 다음과 같다.

    asterisk:
      ami:
        host: ${AMI_HOST:192.168.40.200}
        port: 5038
        username: cti
        password: ctisecret

- `@Value`를 여러 클래스에 분산시키지 않고 접속 정보를 한 곳에 모은다.
- record이므로 기동 이후 변경되지 않는다.
- 클래스에 `@Component`를 붙이지 않는다. 스캔은 `CtiApplication`의
  `@ConfigurationPropertiesScan`이 담당한다.

---

### 2. AmiConnectionConfig

`ManagerConnection`은 asterisk-java가 제공하는 타입이므로 `@Component`를 붙일 수 없다.

외부 라이브러리의 타입을 빈으로 등록하려면 `@Configuration` 클래스의 `@Bean` 메서드를 사용한다.

```java
@Bean
ManagerConnection managerConnection(AmiProperties props) {
    return new ManagerConnectionFactory(props.host(), props.port(), props.username(), props.password())
            .createManagerConnection();
}
```

이 시점에는 객체만 생성되고 접속은 일어나지 않는다.
메서드 이름이 `createManagerConnection`이지만 소켓 연결은 `login()` 시점에 수행된다.

---

### 3. AmiConnectionManager

생명주기 인터페이스로 `SmartLifecycle`을 사용한다.

| | 실행 시점 | 문제 |
|---|---|---|
| `@PostConstruct` | 해당 빈이 생성된 직후 | 다른 빈이 아직 준비되지 않음 |
| `SmartLifecycle.start()` | 모든 빈 생성 완료 후 | 없음 |

AMI 로그인은 그 순간부터 이벤트 수신을 시작한다.
Asterisk는 로그인 직후부터 이벤트를 전송하므로, 수신할 리스너가 아직 등록되지 않았다면
해당 이벤트는 유실된다.

```java
@Override
public void start() {
    try {
        connection.login();
        log.info("AMI login OK: {}:{} (state={})", ...);
    } catch (Exception e) {
        throw new IllegalStateException("AMI login failed", e);
    }
}
```

- 로그인 실패 시 예외를 던져 기동을 중단시킨다. AMI에 접속하지 못한 CTI 서버는
  어떤 기능도 수행할 수 없으므로, 기동에 성공한 채 동작하지 않는 상태보다 낫다.
- `stop()`은 상태가 `CONNECTED`인 경우에만 `logoff()`를 호출한다.
  로그인 실패 후 종료 훅이 실행되면서 2차 예외가 발생하는 것을 막는다.

---

### 4. 리스너 수집

생성자가 `List<ManagerEventListener>`를 받는다.

```java
public AmiConnectionManager(ManagerConnection connection, List<ManagerEventListener> listeners) {
    this.connection = connection;
    connection.addEventListener(event -> log.debug("AMI event: {}", event));
    listeners.forEach(connection::addEventListener);
}
```

Spring이 해당 인터페이스를 구현한 모든 빈을 수집해 주입한다.

- 이후 단계에서 콜 이벤트 번역기, 녹취 리스너, 상담원 상태 리스너가 추가된다.
- 이 클래스는 개별 리스너를 알 필요가 없다. 새 리스너는 `@Component`만 붙이면 연결된다.
- 즉, 리스너가 늘어나도 이 파일은 수정하지 않는다.

현재 주입되는 리스너는 없다. 대신 람다로 등록한 디버그 로그가 연결 상태 확인 용도로 남아 있다.

---

## 다음 단계에서 추가할 것

dialplan이 `UserEvent()`로 전송하는 커스텀 이벤트는 별도 등록이 필요하다.

```java
connection.registerUserEventClass(CtiCallStartedEvent.class);
```

등록하지 않으면 asterisk-java가 전용 타입으로 파싱하지 않는다.

이벤트 클래스는 콜 도메인 단계의 산출물이므로 해당 단계에서 함께 추가한다.

---

## Verification

1. `./gradlew bootRun`
2. 기동 로그 확인

        AMI login OK: 192.168.40.200:5038 (state=CONNECTED)

3. 내선으로 통화를 발생시키면 `AMI event: ...` 로그가 출력된다.
4. `Ctrl+C` 종료 시 예외가 발생하지 않으면 `stop()`까지 정상이다.

---

## Notes

Asterisk를 다른 CTI 서버와 공유하고 있다.

동일한 Asterisk에 동일 계정(`cti`)으로 두 프로세스가 접속하면 같은 이벤트를 각각 수신한다.
로그만 출력하는 현재 단계에서는 문제가 없으나, 호 제어와 DB 기록이 추가되는 단계부터는
하나의 통화가 두 번 처리된다.

실제 통화로 검증할 때는 다른 CTI 서버를 먼저 중지한다.
