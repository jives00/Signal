package com.signal.app

/**
 * Settings → About → Open-source licenses (PLAN §4.3.15): what ships inside the app, and under which
 * license. Every library here must allow CC BY-NC 4.0 on top (PLAN §1); `checkLicenses` keeps GPL out.
 * Add a row when a dependency is added to `shared/` or `androidTvApp/`.
 */
object Credits {
    data class Library(val name: String, val license: String)

    val libraries: List<Library> = listOf(
        Library("FFmpeg (audio decoders)", "LGPL 2.1 or later"),
        Library("AndroidX Media3 (ExoPlayer) and its FFmpeg extension", "Apache 2.0"),
        Library("Jetpack Compose and Compose Multiplatform", "Apache 2.0"),
        Library("AndroidX (Activity, Lifecycle)", "Apache 2.0"),
        Library("Kotlin, kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime", "Apache 2.0"),
        Library("Ktor and OkHttp", "Apache 2.0"),
        Library("SQLDelight", "Apache 2.0"),
        Library("Coil", "Apache 2.0"),
        Library("Vosk and its English speech models", "Apache 2.0"),
        Library("JNA", "Apache 2.0 (or LGPL 2.1)"),
        Library("Lucide icons", "ISC"),
        Library("Archivo typeface", "SIL Open Font License 1.1"),
    )

    /** The LGPL notice for the bundled FFmpeg, with where its exact source and build are (PLAN §5.3). */
    const val FFMPEG_NOTICE =
        "Signal bundles FFmpeg, licensed under the GNU LGPL 2.1 or later, as a separate shared library (libffmpegJNI). " +
            "It's built without GPL or nonfree components. Source: git.ffmpeg.org/ffmpeg.git, branch release/6.0, " +
            "commit b37bf81a7a97. The build configuration and the steps to rebuild it are in " +
            "github.com/jives00/Signal, ffmpeg/BUILD-INFO.md."

    /** Required by TMDB's terms wherever its data or images are shown. */
    const val TMDB_NOTICE = "This product uses the TMDB API but is not endorsed or certified by TMDB."

    const val SIGNAL_LICENSE = "Signal is licensed under CC BY-NC 4.0. Source: github.com/jives00/Signal."
}
