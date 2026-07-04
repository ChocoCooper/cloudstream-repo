package com.AnimeJoker

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeJokerPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI((AnimeJokerProvider))
    }
}
