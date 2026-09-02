package com.KRX18

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Krx18Plugin : Plugin() {
    override fun load(context: Context) {
        registerProvider(Krx18Provider())
        registerExtractorAPI(PlayKrx18Extractor())
        registerExtractorAPI(Mov18plusExtractor())
        registerExtractorAPI(LoadvidExtractor())
    }
}
