package ai.carbuddy.core.geo

/**
 * A hand-simplified outline of South Korea (mainland + Jeju) for the V8 지역 도감 silhouette
 * (UX_SPEC: "전국 시·군 실루엣에 가 본 곳 색칠"). About 45 points, ±5 km — a picture, not a map:
 * no tiles, no network, no licence. Points are (lat, lng), clockwise from the west end of the DMZ.
 */
object KoreaOutline {
    val MAINLAND: List<Pair<Double, Double>> = listOf(
        37.75 to 126.45,                                                        // Ganghwa
        37.95 to 126.68, 38.10 to 127.10, 38.28 to 127.55, 38.32 to 128.05, 38.62 to 128.35,   // DMZ → Goseong
        38.20 to 128.60, 37.75 to 128.95, 37.45 to 129.17, 37.05 to 129.42,     // east coast
        36.60 to 129.40, 36.30 to 129.38, 36.08 to 129.57, 35.80 to 129.48,
        35.55 to 129.45, 35.25 to 129.25, 35.10 to 129.10,                      // Ulsan → Busan
        35.05 to 128.75, 34.85 to 128.60, 34.82 to 128.30, 34.88 to 128.05,     // south coast (Geoje → Namhae)
        34.75 to 127.75, 34.62 to 127.48, 34.50 to 127.20, 34.40 to 126.90,
        34.30 to 126.60, 34.40 to 126.40, 34.65 to 126.30, 34.80 to 126.40,     // Haenam → Mokpo
        35.05 to 126.32, 35.40 to 126.45, 35.65 to 126.55, 35.98 to 126.62,     // west coast (Yeonggwang → Gunsan)
        36.25 to 126.52, 36.55 to 126.22, 36.85 to 126.15, 36.95 to 126.40,     // Boryeong → Taean
        37.00 to 126.75, 37.20 to 126.70, 37.40 to 126.60, 37.58 to 126.55,     // Asan bay → Incheon
    )

    val JEJU: List<Pair<Double, Double>> = listOf(
        33.55 to 126.55, 33.52 to 126.80, 33.45 to 126.95, 33.30 to 126.90, 33.22 to 126.70,
        33.20 to 126.35, 33.30 to 126.16, 33.45 to 126.27,
    )

    val all: List<List<Pair<Double, Double>>> = listOf(MAINLAND, JEJU)
}
