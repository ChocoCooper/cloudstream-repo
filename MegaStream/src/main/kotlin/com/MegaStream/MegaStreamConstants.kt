package com.megastream

object MegaStreamConstants {
    // Massive OMDb Key Rotation Array
    val OMDB_KEYS = listOf(
        "4b447405", "eb0c0475", "7776cbde", "ff28f90b",
        "6c3a2d45", "b07b58c8", "ad04b643", "a95b5205",
        "777d9323", "2c2c3314", "b5cff164", "89a9f57d",
        "73a9858a", "efbd8357"
    )

    // Core Engine URLs
    const val OMDB_BASE_URL = "https://www.omdbapi.com"
    const val STREAMPLAY_URL = "https://streamplay.to"

    // ID-Based Provider Endpoints (Powered by OMDb's IMDb IDs)
    val ID_PROVIDERS = listOf(
        "https://vidsrc.me",
        "https://vidsrc.to",
        "https://superembed.stream",
        "https://multiembed.mov",
        "https://autoembed.to"
    )

    // Extractor Domains List
    val EXTRACTOR_DOMAINS = listOf(
        "voe.sx", "voe.net",
        "filemoon.sx", "filemoon.in", "kerapoxy.cc",
        "upstream.to",
        "dood.watch", "doodstream.com", "dood.to",
        "streamwish.to", "streamwish.com",
        "mixdrop.co", "mixdrop.to"
    )
    
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
}
