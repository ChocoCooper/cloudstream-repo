package com.YoutubeTamil

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YoutubeTamilPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(YoutubeTamilProvider())
    }
}
