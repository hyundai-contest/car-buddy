package ai.carbuddy.core

/**
 * Switches for features that need something we do not have yet. While [COMPANION_SERVER] is false,
 * invite / 폰으로 받기 / 내 폰 연결 show "준비 중" instead of a dead end (운영사무국 문의 중, 11/10).
 */
object Features {
    const val COMPANION_SERVER = false
}
