package com.khflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KhflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KhflixProvider())
    }
}
