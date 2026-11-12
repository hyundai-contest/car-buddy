# Car Buddy

운전하느라 못 남긴 이야기를 대신 기억해 주는 Pleos Connect(Android Automotive) 버디 앱. 운전자가 아무것도 누르지 않아도 주행 중 생긴 일을 에피소드 카드로 만들고, 도착하면 오늘의 길·에피소드·여정 영수증·시상식을 짧은 상영회로 보여 줍니다. 위치는 차 밖으로 나가지 않고, 카드에는 시·군 단위 지명만 남습니다.

- 문서 안내: `docs/README.md`
- 기획: `docs/PLAN_v14.md`(최신) · 시안: `docs/design/card_mockup.html` · 개선 설계: `docs/NEXT_DESIGN.md` · 구현 명세: `docs/SPEC.md` · 화면·문구: `docs/UX_SPEC.md` · 전체 흐름: `docs/E2E.md`
- 구현 중 결정한 것: `docs/IMPLEMENTATION_NOTES.md`
- 에뮬레이터에서 확인한 Pleos SDK 호출: `docs/signals_phase0.md`
- 영상 촬영 가이드: `docs/VIDEO.md`

## 실행 방법

준비물: Android Studio(AGP 9.5, Gradle 9.6, Kotlin 2.4, JDK 17), Pleos Connect 에뮬레이터(`Pleos_Connect_v2.1.0` AVD).

```bash
./gradlew :app:testDebugUnitTest      # 단위 테스트 (165개)
./gradlew :app:assembleDebug          # 디버그 APK (개발자 패널·재생 포함)
./gradlew :app:assembleRelease        # 서명된 릴리스 APK (keystore.properties 필요)
```

에뮬레이터에서 앱은 **user 10**으로 실행됩니다.

```bash
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant --user 10 ai.carbuddy android.car.permission.CAR_SPEED
adb shell pm grant --user 10 ai.carbuddy pleos.car.permission.FUSED_LOCATION
adb shell pm grant --user 10 ai.carbuddy android.permission.ACCESS_FINE_LOCATION
adb forward tcp:8088 tcp:8088         # Virtual Console: http://127.0.0.1:8088/
```

첫 실행 화면에서 동의와 권한을 받으므로 `pm grant`는 자동화 테스트 때만 필요합니다. 에뮬레이터를 재부팅하면 forward를 다시 설정해야 합니다.

## 구조

```
Pleos Vehicle · NaviHelper · FusedLocation · AAOS 차속/화면 제한 · 재생 로그
  → SignalHub → TripPipeline(단일 스레드)
      → TripEngine/TripRecovery (출발·정차·도착·시동 OFF 복구)
      → EpisodeEngine(감지기) → StoryComposer · ReceiptBuilder · RouteShaper → 카드
  → Room (이벤트마다 저장 — 프로세스는 언제든 죽는다고 가정)
  → 화면 (UiModeResolver: 정차 / 주행 DO / 차단) · Pleos TTS · 앰비언트
```

- 주행 중에는 화면을 먼저 띄우지 않습니다. DO 승인 시 한 줄 주행 모드만, 아니면 아무것도 그리지 않습니다.
- 발화는 `SafetyGate.canSpeak()`를 거칩니다(통화·내비 안내·방향지시등 중 금지, 내비 안내가 시작되면 즉시 멈춤).
- 시동 OFF로 프로세스가 죽어도, 프로세스를 넘는 시간은 `System.currentTimeMillis()`로 재고 `TripRecovery.recover()`로 복구합니다.

## 에피소드 규칙 (1차 6종 + 변형 1종)

수치는 `episode/DetectorConfig.kt`에 있습니다(하드코딩 없음).

| 에피소드 | 조건 | 주요 값 |
| --- | --- | --- |
| 뺑뺑이 | 10분 안에 같은 지점(50m 이내)에 2번 이상 다시 옴(150m 이상 벗어났다 와야 함) | 10분, 50m, 2회 |
| 주차 전쟁 | 목적지 500m 안이나 도착 직전의 뺑뺑이(도감 15칸에는 없음) | 500m |
| 계획에 없던 곳 | 주행 중 목적지 변경, 또는 경로에서 2km 넘게 벗어난 곳에 10분 이상 정차 | 2km, 10분, ETA +3분 |
| 소나기 | 와이퍼 꺼짐 → 10분 안에 최고속, 최고속 3분 이상(비 그친 뒤 20분 안에 창문 열면 덧붙임) | 10/3/20분 |
| 온도 전쟁 | 운전석·조수석 목표 온도 차 3℃ 이상이 10분 이상, 조수석 착석 | 3℃, 10분 |
| 정체 | 시속 20km 미만이 20분 이상(30초 이하 회복은 무시) | 20km/h, 20분 |
| 휴게소 | 출발 30분·2km 이후 5분 이상 정차(시동 OFF 포함) | 30분, 2km, 5분 |

## Pleos SDK 사용

SDK 호출은 `signal/adapter/**`, `ceremony/PleosVehicleControls.kt`, `voice/PleosTtsPort.kt` 안에만 있습니다. 문서에 없는 이름은 쓰지 않습니다(`TODO(SDK)`).

| SDK | 버전 | 용도 | 권한 |
| --- | --- | --- | --- |
| Vehicle | 2.3.2 | 기어·벨트·착석·와이퍼(폴링)·창문·목표 온도·방향지시등·전조등·연료·트렁크 / 앰비언트 get·set / 창문 닫기 | `CAR_DRIVE` `CAR_SEATS` `CAR_WINDOWS` `CAR_CLIMATE` `CAR_LIGHTS` `CAR_ENERGY` `CAR_DOORS` `CONTROL_CAR_LIGHTS` `CONTROL_CAR_WINDOWS` |
| NaviHelper | 3.0.5 | 경로 시작·남은 시간·목적지 변경·경유지·재탐색·취소·도착·카메라·다음 회전 | `NAVI_ROUTE` |
| FusedLocation | 1.1.3 | 위치(미지원이면 Android GPS) | `FUSED_LOCATION`(런타임) |
| TextToSpeech | 3.3.1 | 도착 회고, 상영회 코멘트(ON_DEVICE) | `TTS_SERVICE` |
| AAOS Car API | — | 차속(`PERF_VEHICLE_SPEED(_DISPLAY)`), `CarUxRestrictions` | `CAR_SPEED`(런타임) |

에뮬레이터 주의: 착석 값이 반대로 와서 에뮬레이터에서만 뒤집어 씀, 콘솔에 벨트 조작 없음, 재부팅하면 내비 안내가 풀림. 자세한 내용은 `docs/signals_phase0.md`.

## 재생 로그

`docs/replay/*.jsonl`은 한 줄에 이벤트 하나(`{"t":…,"type":"Gear","gear":"D"}`). 테스트 픽스처이자 앱 assets입니다.

- `scenario_a.jsonl`: 토요일 서울 → 강릉. 정체·온도 전쟁·휴게소(시동 OFF 20분, 복구)·계획에 없던 곳·뺑뺑이·소나기, 지각 도착.
- `commute_b.jsonl`: 평일 퇴근, 목적지 없음. 정체·편의점(시동 OFF 8분)·지하주차. 3번 이어 붙이면 3번째가 '일상'.
- 생성: `python tools/gen_scenario_a.py > docs/replay/scenario_a.jsonl` (이 PC에서는 `python3` 대신 `python`)
- 디버그 빌드 왼쪽 아래 **DEV** → 재생 패널: 로그 선택, 1×/10×/60×/300×, wall clock. 재생 중에는 실차의 위치·속도·기어·벨트·내비 신호를 무시하고, 창문·온도 같은 실내 신호는 그대로 받습니다.
- **데이터 초기화**(개발자 패널): DB 전체 삭제 후 앱 재시작(영상 반복 촬영용).

## 릴리스 서명

`keystore.properties`(저장소 루트, gitignore)에 키스토어 경로와 비밀번호를 둡니다. 없으면 서명 없이 빌드됩니다.

```
storeFile=C:/Users/<user>/.carbuddy/carbuddy-release.jks
storePassword=…
keyAlias=carbuddy
keyPassword=…
```

키스토어와 비밀번호는 반드시 따로 백업하세요. 잃어버리면 마켓 앱을 업데이트할 수 없습니다.
