package com.asiaflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AsiaflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AsiaflixProvider())
    }
}
