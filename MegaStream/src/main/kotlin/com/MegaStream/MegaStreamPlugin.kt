package com.megastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MegaStreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MegaStreamProvider())
    }
}
