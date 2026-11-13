# Car Buddy 문서 안내

2026-11-13 기준. 처음이라면 위에서부터 읽으면 됩니다.

## 저장소에 있는 문서

| 문서 | 내용 | 언제 보나 |
| --- | --- | --- |
| [PLAN_v16.md](PLAN_v16.md) | 기획안 상세본(공모전 02 양식 7개 항목 순서). 심사위원 체험 방법, 차량 특화 요소, 에피소드 14종, 시나리오, SDK 사용 현황, Pleos 심사 가이드 반영(11/13), 일정 | 무엇을 왜 만드는지 |
| [E2E.md](E2E.md) | 실사용 흐름: 출발, 목적지 없는 날, 도착, 다음 탑승 | 실제 쓰이는 순서 |
| [design/card_mockup.html](design/card_mockup.html) | 카드·화면 시안 13장. 브라우저로 열면 됨 | 화면이 어떻게 생겼는지 |
| [video/CarBuddy_demo.mp4](video/CarBuddy_demo.mp4) | 시연 영상(1분 47초, 에뮬레이터 화면 녹화). 직접 조작한 실제 구현과 주행 로그 재생(모의 데이터)을 자막으로 구분 | 동작을 눈으로 볼 때 |
| [video/narration_script.txt](video/narration_script.txt) | 시연 영상 내레이션 | 영상 내용 확인 |

## 참고 자료

| 자료 | 내용 |
| --- | --- |
| [signals_phase0.md](signals_phase0.md) | Pleos 에뮬레이터에서 실제로 확인한 SDK 호출과 Virtual Console HTTP 조작법 |
| [replay/scenario_a.jsonl](replay/scenario_a.jsonl) | 주행 재생 로그: 토요일 서울 → 강릉(에피소드 6종, 휴게소 시동 OFF, 지각 도착) |
| [replay/commute_b.jsonl](replay/commute_b.jsonl) | 주행 재생 로그: 평일 퇴근, 목적지 없음 |

재생 로그는 테스트 픽스처이자 앱 assets라 파일 이름과 위치를 바꾸면 안 됩니다. 생성 스크립트는 저장소의 `tools/`에 있습니다.
