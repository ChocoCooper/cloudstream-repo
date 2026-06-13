package com.megastream

object MegaStreamConstants {
    // Massive OMDb Key Rotation Array
    val OMDB_KEYS = listOf(
        "4b447405", "eb0c0475", "7776cbde", "ff28f90b",
        "6c3a2d45", "b07b58c8", "ad04b643", "a95b5205",
        "777d9323", "2c2c3314", "b5cff164", "89a9f57d",
        "73a9858a", "efbd8357"
    )

    const val OMDB_BASE_URL = "https://www.omdbapi.com"

    // --- RESTORED FROM YOUR ApiConstants.kt ---
    // These proxy endpoints bypass the ISP blocks on standard domains
    const val VIDSRC_PROXY = "https://api.rgshows.ru"
    const val VIDSRC_HINDI = "https://hindi.rgshows.ru"
    const val PRIMESRC_API = "https://primesrc.me"
    const val DAHMER_MOVIES = "https://a.111477.xyz"
    const val HEXA_API = "https://theemoviedb.hexa.su"
    const val VIDEASY_API = "https://api.videasy.to"
    const val VIDLINK_API = "https://vidlink.pro"
    const val TWO_EMBED_API = "https://2embed.cc"
}
