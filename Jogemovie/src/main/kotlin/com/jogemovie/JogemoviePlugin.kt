package com.jogemovie

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JogemoviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JogemovieProvider())
    }
}
