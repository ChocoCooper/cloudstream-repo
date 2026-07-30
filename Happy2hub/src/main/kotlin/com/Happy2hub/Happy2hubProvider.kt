package com.Happy2hub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Happy2hubProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Happy2hub())
    }
}
