# CLAUDE.md — Car Buddy

Pleos Connect(Android Automotive) 차량용 앱 공모전 출품작. 구현 기준은 docs/SPEC.md, 화면·흐름·문구 기준은 docs/UX_SPEC.md, 실제 사용 상황(출발·목적지 없음·도착·다음 탑승)의 전체 흐름은 docs/E2E.md다. 작업 전 반드시 해당 장을 읽는다. 구현 중 정한 세부 결정은 docs/IMPLEMENTATION_NOTES.md.

## 저장소
- 저장소 루트 = `vehicle-app` (Android Studio 프로젝트, 모듈 `:app`). Kotlin, Jetpack Compose, Room, Hilt, Coroutines/Flow. minSdk 28, AGP 9.5 / Gradle 9.6, Kotlin 2.4.
  - 패키지 `ai.carbuddy`: core, signal(adapter/replay), trip, route, place, episode(detectors), story, receipt, voice, ceremony, card, data, ui, devtools, di
- `companion-server/` Spring Boot 3, Java 21, PostgreSQL, Redis, Flyway. 모듈러 모놀리스(패키지 = 도메인). (PT, 아직 없음)
- `phone-web/` Vite + React + TypeScript. (PT, 아직 없음)
- `docs/replay/*.jsonl` 주행 재생 로그(테스트 픽스처 겸용, 앱 assets로도 포함됨). 생성기는 `tools/`.
- `docs/signals_phase0.md` 이전 프로토타입에서 Pleos 에뮬레이터로 실제 확인한 SDK 호출과 콘솔 HTTP API.

## 절대 규칙
1. **SDK API를 지어내지 않는다.** SPEC.md에서 [확인됨]인 이름(또는 docs/signals_phase0.md에서 에뮬레이터로 확인한 이름)만 쓴다. [TBD] 항목은 어댑터 인터페이스 + `TODO(SDK)` 주석으로 남기고, 추측한 메서드명을 코드에 쓰지 않는다.
2. Pleos/AAOS 호출은 `signal/adapter` 패키지, `voice/*Port` 구현체, `ceremony/AmbientController`·`VehicleControlPort` 구현체 안에서만 한다. 그 밖의 코드는 `SignalEvent`와 Port 인터페이스만 의존한다.
3. 화면 모드는 `UiModeResolver`(PARKED / DRIVING_DO / BLOCKED)를 반드시 거친다. 주행 중에는 V10 주행 모드만 그리고, 앱이 먼저 화면을 띄우지 않는다. 발화는 `SafetyGate.canSpeak()`를 거친다.
4. 상태는 이벤트마다 Room에 저장한다. 시동 OFF로 프로세스가 언제든 죽는다고 가정한다. 프로세스를 넘는 시간은 `System.currentTimeMillis()`로만 재고(`elapsedRealtime` 금지), 복구는 `TripRecovery.recover()` 하나로만 한다(SPEC 5.2·5.3).
5. 감지 수치는 `DetectorConfig`에만 둔다. 하드코딩 금지.
6. 위경도·원본 궤적은 서버로 보내지 않는다. 카드 JSON(SPEC 11장)에 위치 필드를 추가하지 않는다. 경로는 RouteShaper가 앞뒤를 잘라내고 정규화한 `routeShape`만 내보낸다(SPEC 10.1).
7. 사용자 문구는 `res/values/strings_copy.xml`(CopyBook) 키로만. 문구 톤: 버디 1인칭 '우리', 비난·운전 평가·속도 수치 금지. 경로 선도 속도로 색칠하지 않는다(에피소드 색만). "만회"·"빨리 왔"·"칼같이"는 `CopyLintTest`가 막는다.
8. 모든 화면은 Portrait(창 1개, 기본)·Landscape(창 2개) 레이아웃을 함께 만든다. 홈에서 Back은 앱 종료가 아니라 `moveTaskToBack(true)`.
9. 폰트는 Asta Sans(res/font, OFL), 최소 12sp. 버튼 눌림은 95% 축소 + 배경 변화(`CbButton`).
10. KDoc/주석 안에 `/**`나 `/*`를 쓰지 않는다(Kotlin은 블록 주석이 중첩돼 파일 나머지가 주석이 됨).

## 작업 방식
- 한 번에 SPEC.md 18장의 Step 하나씩. 시작할 때 계획을 짧게 말하고, 끝나면 DoD 결과(테스트 출력)를 보여 준다.
- 새 감지기·상태 전이는 테스트 먼저(합성 이벤트 또는 JSONL 픽스처). 경계값 테스트 포함.
- 기존 구조·네이밍을 따르고, 요청 없는 대규모 리팩토링 금지.
- 모르는 건 모른다고 하고 SPEC 19장 '확인할 것'에 추가한다.

## 명령 (저장소 루트에서)
- 차량 앱 테스트: `./gradlew :app:testDebugUnitTest`
- APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- 재생 로그의 `PowerCycle`(REBOOT/SUSPEND/LATE_START)은 테스트 하네스(`TestSupport.runWithPowerCycles`)가 프로세스 재시작으로 처리한다.
- 재생 로그 생성: `python tools/gen_scenario_a.py > docs/replay/scenario_a.jsonl`, `python tools/gen_commute_b.py > docs/replay/commute_b.jsonl` (이 PC의 `python3`는 MS Store 스텁이라 `python` 사용)
- 에뮬레이터 Virtual Console: `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088/ (앱은 user 10으로 실행, 권한은 `adb shell pm grant --user 10 ai.carbuddy …`)
- 서버(PT): `./gradlew test`, `./gradlew bootRun --args='--spring.profiles.active=local'` / 폰 웹(PT): `npm run dev`
