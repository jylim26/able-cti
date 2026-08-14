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