package com.isaidub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IsaidubPlugin : Plugin() {
    override fun load(context: Context) {
        // Registers the provider. When you add KuttyMovies, register it here too:
        // registerMainAPI(KuttyMoviesProvider())
        registerMainAPI(IsaidubProvider())
    }
}
