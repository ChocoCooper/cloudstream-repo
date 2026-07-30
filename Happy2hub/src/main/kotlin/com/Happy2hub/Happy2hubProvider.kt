package com.Happy2hub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.LuluStream

@CloudstreamPlugin
class Happy2hubProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Happy2hub())
        registerExtractorAPI(Voe())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(LuluStream())
    }
}
