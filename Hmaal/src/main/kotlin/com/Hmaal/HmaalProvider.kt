package com.Hmaal

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HmaalProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Hmaal())
    }
}
