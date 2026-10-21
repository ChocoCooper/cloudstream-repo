package com.tamilbulb

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TamilbulbPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TamilbulbProvider())
        registerExtractorAPI(N1mwqExtractor())
        registerExtractorAPI(TamilgunExtractor())
    }
}
