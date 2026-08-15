# 코딩 컨벤션

Date: 2026-08-14

## 주석

- 코드에 주석을 달지 않는다. 요청받은 부분에만 단다.
- 코드로 표현할 수 없는 비자명한 제약은 주석 대신 `docs/`에 기록한다.

---

## Lombok

- 적극 활용하되 `@Data`, `@Setter`는 지양한다. 무분별한 가변 상태를 막기 위해서다.
- 권장하는 애너테이션은 다음과 같다.
  - `@Getter`
  - `@RequiredArgsConstructor`
  - `@Slf4j`
  - `@Builder`
- 단순 값 묶음은 Lombok보다 Java `record`를 우선한다.

- 지양하는 것은 애너테이션이 아니라 setter 자체다.
  라이브러리가 값을 채워 넣어야 해서 setter를 뺄 수 없는 클래스라면,
  손으로 쓰지 말고 `@Setter`를 쓴다. 손으로 써도 가변인 것은 똑같고 길기만 하다.

  예: `CtiCallStartedEvent`는 asterisk-java가 AMI 헤더를 setter로 채운다.

- `@Getter`는 클래스에 붙이고, 공개하지 않을 필드에만 `@Getter(AccessLevel.NONE)`을 붙인다.
  필드가 늘어날수록 예외를 표시하는 쪽이 짧고, 예외가 눈에 띈다.

---

## Optional

- 값이 없을 수 있는 조회 메서드는 `Optional`을 돌려준다. `null`을 돌려주지 않는다.
- 찾았을 때만 처리하는 경우는 `ifPresent`를 쓴다.

      registry.find(linkedid).ifPresent(call -> call.legStarted(...));

  `if (call == null) return;`으로 풀어 쓰는 방식도 흔하지만,
  "찾았을 때만 일한다"가 한 줄에 드러나는 쪽을 택한다.

- `Optional`은 돌려주는 값에만 쓴다. 필드나 파라미터에는 쓰지 않는다.

---

## 설정 주입

- `@Value("${...}")` 대신 `@ConfigurationProperties` record를 만든다.

  근거는 다음과 같다.

  - 관련 설정의 그룹화
  - 타입 안전
  - 기동 시점 검증 (`@Validated`)
  - IDE 자동완성
  - 테스트에서 POJO로 생성 가능

  트레이드오프도 있다. 값이 한두 개인 단발성 설정에는 클래스 생성이 과하다.
  그 경우에만 `@Value`를 허용한다.

- Properties 클래스는 애플리케이션 클래스의 `@ConfigurationPropertiesScan`으로 등록한다.
  개별 클래스에 `@Component`를 붙이지 않는다.

---

## 패키지 구조

- 횡단 관심사는 `global` 아래에 둔다 (`global/config`, `global/error`).
- global 판단 기준은 사용 범위가 아니라 소유권이다.

  > 여러 모듈이 쓰니까가 아니라, 특정 모듈에 속하지 않아야 global이다.

  예를 들어 `AmiProperties`는 여러 곳에서 참조되더라도 `ami` 모듈 소유다.

---

## Java 포맷

- 메서드와 생성자의 파라미터 목록, 호출부의 인자 목록은 길이와 관계없이 한 줄로 작성한다.
- 블록 본문이 있는 lambda와 익명 클래스, `if` / `for` / `switch` 같은 제어문은
  이 규칙의 대상이 아니다.

---

## 커밋

- commit-changes 스킬 규칙을 따른다.
- 형식은 `타입: 메시지`로 쓴다.
- `Co-Authored-By`와 생성 문구를 넣지 않는다.