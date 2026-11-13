# Car Buddy

> **운전하느라 못 남긴 이야기를, 차가 대신 기억해 주는 Pleos Connect 버디 앱.**

운전자가 아무것도 누르지 않아도 주행 중 생긴 일(정체, 온도 전쟁, 계획에 없던 곳, 뺑뺑이, 소나기 …)을 에피소드 카드로 만들고, 도착하면 오늘의 길·에피소드·여정 영수증·시상식을 짧은 상영회로 보여 줍니다. 위치는 차 밖으로 나가지 않고, 카드에는 시·군 단위 지명만 남습니다.

| 항목 | 내용 |
| --- | --- |
| 앱 이름 | Car Buddy (패키지 `ai.carbuddy`, 버전 0.1.0) |
| 대상 | Pleos Connect(Android Automotive) 차량용 앱 |
| APK | [`apk/CarBuddy_release.apk`](apk/CarBuddy_release.apk) |
| 시연 영상 | [`docs/video/CarBuddy_demo.mp4`](docs/video/CarBuddy_demo.mp4) (1분 47초, 에뮬레이터 화면 녹화) |
| 기획안 | [`docs/PLAN_v16.md`](docs/PLAN_v16.md) |
| 문서 안내 | [`docs/README.md`](docs/README.md) |

## APK 파일 위치

- 저장소 안: **`apk/CarBuddy_release.apk`** (서명된 릴리스 빌드, 약 27.6 MB)
- 바로 받기: https://github.com/hyundai-contest/car-buddy/blob/main/apk/CarBuddy_release.apk
- GitHub Release는 따로 만들지 않았습니다. 최신 APK는 항상 위 경로에 있습니다.
- SHA-256: `d52c662e7c10c1137a50cc1dc848a529d22798aba944682e554dc6f5ba3b9bd4`
- 서명 인증서: `CN=Car Buddy, O=Car Buddy, C=KR`

## 실행 방법

### 1) 제출 APK로 실행 (소스 빌드 불필요)

1. Pleos Connect 에뮬레이터(`Pleos_Connect_v2.1.0`)를 부팅합니다.
2. APK를 **user 10**에 설치합니다(에뮬레이터에서 앱 사용자는 10입니다).

   ```bash
   adb install -r --user 10 apk/CarBuddy_release.apk
   ```

3. 앱 목록에서 **Car Buddy**를 실행합니다. 첫 실행 화면에서 동의 → 위치·차량 신호 권한 → 버디 이름을 정하면 홈이 열립니다.
4. 이후에는 앱을 열 필요가 없습니다. 출발하면 알아서 기록하고, 도착하면 상영회를 보여 줍니다.

릴리스 APK에는 개발자 패널(DEV)이 없습니다. 주행 로그 재생으로 전체 흐름(출발 → 휴게소 → 도착 상영회)을 보려면 아래 2)의 디버그 빌드를 설치하세요.

### 2) 소스에서 빌드해 실행 (개발자 패널·주행 로그 재생 포함)

준비물: Android Studio(AGP 9.5, Gradle 9.6, Kotlin 2.4, JDK 17), Pleos Connect 에뮬레이터.

```bash
./gradlew :app:testDebugUnitTest      # 단위 테스트 (171개)
./gradlew :app:assembleDebug          # 디버그 APK (개발자 패널·재생 포함)
./gradlew :app:assembleRelease        # 서명된 릴리스 APK (keystore.properties 필요)
```

```bash
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
adb forward tcp:8088 tcp:8088         # Virtual Console: http://127.0.0.1:8088/
```

앱 안에서 보는 순서: 홈 왼쪽 아래 **DEV** → 재생 패널에서 `scenario_a`(서울 → 강릉) 선택 → 60×로 재생. 휴게소에서 다시 벨트를 매면 버디가 "쉬는 동안 지금까지 볼래?"라고 묻고, 도착하면 상영회가 열립니다. 심사위원용 체험 방법은 [`docs/PLAN_v16.md`](docs/PLAN_v16.md) 5장, 시연 영상은 [`docs/video/CarBuddy_demo.mp4`](docs/video/CarBuddy_demo.mp4).

## 테스트 환경 및 필요한 설정

| 항목 | 값 |
| --- | --- |
| 개발·테스트 PC | Windows 11, Android Studio |
| 빌드 | AGP 9.5, Gradle 9.6, Kotlin 2.4, JDK 17, compileSdk/targetSdk 37, minSdk 28 |
| 의존 저장소 | Maven Central, Pleos SDK 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/` (빌드할 때만 필요) |
| 실행 환경 | Pleos Connect 에뮬레이터, 앱 사용자 **user 10** |
| 차량 신호 조작 | 에뮬레이터 Virtual Console (`adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088/) |
| 네트워크 | 필요 없음(서버 통신 없음). 위치·원본 궤적은 차 밖으로 나가지 않습니다 |

필요한 설정과 권한

- 첫 실행 화면에서 동의와 권한을 받습니다. 자동화 테스트나 재설치 때는 직접 줄 수 있습니다.

  ```bash
  adb shell pm grant --user 10 ai.carbuddy android.car.permission.CAR_SPEED
  adb shell pm grant --user 10 ai.carbuddy pleos.car.permission.FUSED_LOCATION
  adb shell pm grant --user 10 ai.carbuddy android.permission.ACCESS_FINE_LOCATION
  adb shell pm grant --user 10 ai.carbuddy android.permission.POST_NOTIFICATIONS
  ```

- 에뮬레이터를 재부팅하면 `adb forward`를 다시 설정합니다.
- TTS 소리를 들으려면 에뮬레이터를 `-no-audio` 없이 띄워야 합니다. 에뮬레이터에는 기본 TTS 엔진이 없어 실제 소리는 확인하지 못했습니다(실차 확인 필요).
- 에뮬레이터에서만 다른 점: 착석 값이 반대로 와서 앱이 뒤집어 읽고, 콘솔에 운전석 벨트 조작이 없어 벨트·위치 궤적·내비 도착 이벤트는 주행 로그 재생(모의 데이터)으로 시험했습니다. 구분은 [`docs/PLAN_v16.md`](docs/PLAN_v16.md)의 '구현한 부분과 모의 데이터로 대체한 부분', 자세한 값은 [`docs/signals_phase0.md`](docs/signals_phase0.md).

## 개발에 사용한 에뮬레이터와 화면 규격

| 항목 | 값 |
| --- | --- |
| 에뮬레이터 | Pleos Connect 에뮬레이터 **v2.1.0** (AVD 이름 `Pleos_Connect_v2.1.0`) |
| 시스템 이미지 | `android-34` · `pleos_car_emulator` · x86_64 (Android Automotive, API 34) |
| 화면 | **2560 × 1440 px**, 밀도 160 dpi(1dp = 1px), 가로 화면 |
| 화면 구성 | 왼쪽 차량 패널 + 오른쪽 앱 창(2창 모드, 앱 창 약 1680 × 1210 px). 1창 모드는 세로(Portrait) 배치로 대응 |
| 대응 | 모든 화면을 Portrait(창 1개)·Landscape(창 2개) 레이아웃으로 함께 만들었습니다 |

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
