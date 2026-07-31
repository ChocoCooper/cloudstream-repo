package com.hmaal

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HmaalPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HmaalProvider())
    }
}
