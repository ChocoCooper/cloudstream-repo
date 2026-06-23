package com.YT

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YTPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(YTProvider())
    }
}
