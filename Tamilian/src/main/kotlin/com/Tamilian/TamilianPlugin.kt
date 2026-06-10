package com.Tamilian

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TamilianPlugin : Plugin() { 
    override fun load(context: Context) {
        registerMainAPI(Tamilian())
    }
}
