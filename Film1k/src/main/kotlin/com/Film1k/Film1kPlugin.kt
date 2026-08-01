package com.film1k

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Film1kPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Film1kProvider())
        registerExtractorAPI(Film1kExtractor())
    }
}
