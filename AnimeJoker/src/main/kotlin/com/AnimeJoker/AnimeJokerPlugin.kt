package com.AnimeJoker

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeJokerPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeJokerProvider())
    }
}
