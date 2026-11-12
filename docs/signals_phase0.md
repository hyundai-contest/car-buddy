# 신호 확인표 (Phase 0, 2026-11-07, Pleos_Connect_v2.1 에뮬레이터)

| 신호 | 앱에서 읽는 방법 | 결과 | 비고 |
|---|---|---|---|
| 기어 | Pleos `Gear.registerGearSelection` | ✅ 콘솔 P/D 변경 수신 | GEAR_1~9는 D로 처리 |
| 차속 | AAOS `PERF_VEHICLE_SPEED_DISPLAY` (+ `PERF_VEHICLE_SPEED`) | ✅ 콘솔 60km/h 수신 | 에뮬레이터는 DISPLAY 쪽만 발행. `CAR_SPEED`는 **user 10**에 권한 필요 |
| 문 | `Door.registerDoorPosition` (좌석별) | ✅ 운전석 열림/닫힘 수신 | 값 > 0 이면 열림 |
| 창문 | `Window.registerWindowPosition` (좌석별) | ✅ | SDK 값 0–10 = 콘솔 0–100%. 앱에서 ×10으로 % 변환 |
| 와이퍼 | `Wiper.getCurrentWiperSwitch` 2초 폴링 | ✅ OFF 수신 | 단계용 리스너 없음. CONTINUOUS_3~5 = HIGH |
| 착석 | `Seat.registerSeatOccupancy` | ⚠️ 콘솔과 항상 반대 | SDK `SeatImpl`이 원시값 1을 착석으로 매핑, AAOS는 1=VACANT·2=OCCUPIED. 에뮬레이터에서만 앱이 뒤집음(11/8) |
| 좌석별 목표 온도 | `HVAC.registerHvacTemperature(SeatArea)` | ✅ 운전석·조수석 수신 | |
| 방향지시등 | `TurnSignal.registerTurnSignalState` | ✅ | 비상등 값 없음 → THANKS_HAZARD 제외 |
| 앰비언트 | `Light.get/setAmbientLightBrightnessColor` | ✅ 시상식 Cyan → 닫기 후 원래 색 복원 | 영역은 capability가 주는 0x75(전 좌석), 색은 24bit RGB(알파 없음) |
| TTS | Pleos TextToSpeech `ON_DEVICE` | ⚠️ 오류 없음, 실제 소리는 미확인 | 에뮬레이터에 안드로이드 기본 TTS 엔진 없음 |
| NaviHelper / FusedLocation | — | ✅ 11/8 연결 (아래 표) | Maven 저장소에 NaviHelper 3.0.5, FusedLocation 1.1.3 있음 |

## Step 8 연결 결과 (2026-11-08, Car Buddy 앱 `signal/adapter`)

| 신호 | 호출 | 결과 |
|---|---|---|
| 운전석 벨트 | `Seat.registerSeatBeltBuckledState(SeatArea(ROW_1_LEFT))` | ✅ false 수신 |
| 하향등·상향등 | `Light.registerLowBeamLightState` / `registerHighBeamLightState` | ✅ OFF 수신 |
| 트렁크 | `Door.registerDoorPosition(DoorArea(DoorValue.REAR))` | ✅ 닫힘 수신 |
| 연료 | `Fuel.registerFuelPercent` | ⚠️ 오류도 값도 없음(전기차 에뮬레이터로 보임) |
| 좌석별 목표 온도 | `HVAC.registerHvacTemperature` | ⚠️ 부팅 직후 PropertyNotAvailable 후 22.0 수신 → 값이 오면 지원으로 되돌림 |
| 와이퍼 | `getCurrentWiperSwitch` 2초 폴링 | ✅ (`WiperState`는 ON/OFF뿐이라 단계용으로 못 씀) |
| 화면 제한 | AAOS `CarUxRestrictionsManager` | ✅ D+60km/h에서 DO=true, P에서 false |
| 위치 | Pleos `FusedLocation.registerFusedLocationCallback` (isSupported → initialize) | ✅ 고정 좌표(37.422, −122.084). `FUSED_LOCATION`은 **dangerous**(런타임 허용 필요). 미지원이면 Android GPS로 |
| 내비 경로 시작 | NaviHelper `onRouteStarted(RouteStartInfo)` + `getDestinationInfo()` | ✅ 강남파이낸스센터, duration 1680초 → RouteStarted(이름·좌표·ETA) |
| 남은 시간 | `getDestinationInfo()` 10초 폴링 → `DestinationInfo.remainTime`(초) | ✅ |
| 경로 취소 | `onRouteCancelled` | ✅ |
| 안내 중 여부 | `getRouteStateInfo().naviMode == ROUTE_GUIDING` | ✅ `isRouting`은 안내 중에도 false(경로 탐색 중 의미로 보임) |
| 앱 재시작 시 경로 복원 | 연결 직후 위 조건이면 RouteStarted 재전송 | ✅ |
| 도착·경유지·재탐색·카메라 | `onDestinationArrived`, `onWaypointChanged`, `onReRouteEnded`, `onCameraAlert` | ⏳ 코드만. 에뮬레이터에서 차가 실제로 움직이지 않아 미확인 |
| 내비 정보 콜백 | `onWayPointInfo`·`onDestinationInfo`는 요청 없이도 옴(취소 직후 등) | 우리가 요청한 응답만 처리 |
| 콘솔 조작 → 여정 | Drive+60km/h → 여정 시작, Park → PAUSED | ✅ |

## Step 9 연결 결과 (2026-11-08)

| 기능 | 호출 | 결과 |
|---|---|---|
| 앰비언트 저장 | `Light.getAmbientLightBrightnessColor(SeatArea 5석)` | ✅ #ffffff |
| 앰비언트 적용·복원 | `setAmbientLightBrightnessColor(area, ARGB)` | ✅ VHAL 로그 `set ambient lights color: (0,229,255)` → 복원 `(255,255,255)`. **`CONTROL_CAR_LIGHTS` 권한 필요**(문서 주석은 CAR_LIGHTS). 콘솔 화면 상태와 직후 get 값은 안 바뀜 |
| 창문 닫기 | `Window.setWindowPosition(WindowArea, 0)` (`CONTROL_CAR_WINDOWS`) | ✅ VHAL `set window position area: Row1Right, position: 0` |
| TTS | Pleos `TextToSpeech` ON_DEVICE | ✅ 초기화·onStart·onDone. 소리는 미확인 |

## Virtual Console HTTP API (테스트 자동화용)

전체 조작 목록은 `GET /vehicle/catalog`(기능별 write_endpoint·파라미터). 11/8 확인한 것:
- 착석: `PUT /vehicle/seat?kind=occupied&area=driver&value=true` (driver/passenger/rear_left/rear_right)
- 와이퍼: `PUT /vehicle/body?front_wiper=High` (Off/Auto/Low/High)
- 전원 상태: `PUT /vehicle/power-system/simulator?area_id=0&state=Off` — 값만 바뀌고 안드로이드는 그대로
- 경고등: `PUT /vehicle/telltales/simulator?type=SeatBelt&state=On&color=Red` — 벨트 체결 값은 안 바뀜
- 벨트 체결 조작은 없음

쓰기 전에 매번 세션을 열어야 합니다(유효 시간 3초).

```bash
B=http://127.0.0.1:8088
curl -X POST "$B/browser/session?client=test"
curl -X PUT  "$B/vehicle/gear?gear=Park"          # Park | Reverse | Neutral | Drive
curl -X PUT  "$B/vehicle/controls?speed=60"
curl -X PUT  "$B/vehicle/body?door_fl=true"       # door_fl/fr/rl/rr, window_fl..=0~100
curl         "$B/vehicle/state"                   # 전체 상태(앰비언트 색 포함)
```

## 에뮬레이터 주의
- 앱은 **user 10**으로 실행됩니다. 권한 부여: `adb shell pm grant --user 10 com.example.myapplication android.car.permission.CAR_SPEED`
- 권한 일괄 부여: `adb shell pm grant --user 10 ai.carbuddy android.car.permission.CAR_SPEED`, `pleos.car.permission.FUSED_LOCATION`, `android.permission.ACCESS_FINE_LOCATION`
- 에뮬레이터를 재부팅하면 이 권한과 `adb forward tcp:8088 tcp:8088`이 초기화됩니다.
