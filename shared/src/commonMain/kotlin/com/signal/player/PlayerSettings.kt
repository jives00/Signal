package com.signal.player

/**
 * Settings → Playback (PLAN §4.3.15 → 5), as the player takes them. Languages are per profile;
 * subtitle style and the technical settings are per device, since they depend on the TV, the
 * receiver and the network. Like the rest of the player layer, nothing here knows about IPTV (PLAN §11.2).
 */
data class PlayerSettings(
    /** Preferred audio languages, best first, as ISO 639 codes ("en", "es"). Empty: the stream's default. */
    val audioLanguages: List<String> = emptyList(),
    /** Preferred subtitle language; null is the device's own language. */
    val subtitleLanguage: String? = null,
    val subtitles: SubtitleMode = SubtitleMode.FORCED,
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val subtitleColor: SubtitleColor = SubtitleColor.WHITE,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.OUTLINE,
    val decoder: DecoderMode = DecoderMode.HARDWARE_FALLBACK,
    val frameRate: FrameRateMode = FrameRateMode.ON,
    /** Auto: AC3, E-AC3 and DTS go to the receiver when the HDMI chain supports them. Off: always decoded here. */
    val passthrough: Boolean = true,
    val tunneling: Boolean = false,
    val liveBuffer: LiveBuffer = LiveBuffer.BALANCED,
    val vodBuffer: VodBuffer = VodBuffer.NORMAL,
    val timeoutSeconds: Int = 20,
    /** Automatic retries after a stream fails, before the error shows (and failover, for live). */
    val retries: Int = 1,
) {
    companion object {
        val TIMEOUTS = listOf(10, 20, 30)
        val RETRIES = 0..3
    }
}

enum class SubtitleMode(val label: String, val detail: String) {
    OFF("Off", "Only when you pick a track"),
    FORCED("Forced only", "Just the lines for foreign dialogue, when a stream marks them"),
    ALWAYS("Always", "In your subtitle language, or captions when there's no other"),
}

enum class SubtitleSize(val label: String, val scale: Float) {
    SMALL("Small", 0.8f), NORMAL("Normal", 1f), LARGE("Large", 1.3f), EXTRA_LARGE("Extra large", 1.6f),
}

enum class SubtitleColor(val label: String, val argb: Long) {
    WHITE("White", 0xFFFFFFFF), YELLOW("Yellow", 0xFFFFE14D), CYAN("Cyan", 0xFF7FE6FF),
}

enum class SubtitleBackground(val label: String) {
    OUTLINE("None, outlined text"), SHADED("Shaded box"), BLACK("Black box"),
}

enum class DecoderMode(val label: String, val detail: String) {
    HARDWARE("Hardware", "The TV's video decoder only"),
    HARDWARE_FALLBACK("Hardware, software fallback", "Software when the hardware decoder fails to start"),
    SOFTWARE("Software", "Slower; for streams the hardware decoder shows wrongly"),
}

enum class FrameRateMode(val label: String, val detail: String) {
    OFF("Off", "The display stays as it is"),
    ON("On", "Switches the refresh rate to the video's, like 23.976 or 59.94"),
    ON_RESOLUTION("On, also switch resolution", "Also the resolution, so the TV does the upscaling"),
}

enum class LiveBuffer(val label: String, val detail: String, val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int) {
    LOW_LATENCY("Low latency", "Closest to live, more likely to stall", 5_000, 15_000, 500, 1_000),
    BALANCED("Balanced", "Fast channel changes, a short cushion", 15_000, 30_000, 1_000, 2_000),
    STABLE("Stable", "A larger buffer and more delay, for shaky connections", 30_000, 60_000, 2_500, 5_000),
}

enum class VodBuffer(val label: String, val detail: String, val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int) {
    // The same as live used to give on-demand (0.2.x): a 4K stream at 50 s would crowd the Shield's 192 MB heap.
    NORMAL("Normal", "About half a minute ahead", 15_000, 30_000, 2_500, 5_000),
    LARGE("Large", "Up to two minutes ahead, for slow or uneven connections", 60_000, 120_000, 2_500, 5_000),
}
