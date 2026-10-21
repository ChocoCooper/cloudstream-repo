package com.skybap

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SkyBapPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SkyBapProvider())

        registerExtractorAPI(SkyBapWorkersDev())      // *.workers.dev (NEW — fixes the 403s)
        registerExtractorAPI(SkyBapPixeldrain())      // pixeldrain.com + pixeldrain.dev
        registerExtractorAPI(SkyBapPixelHubcloud())   // pixel.hubcloud.ist
        registerExtractorAPI(SkyBapBusyCdn())         // instant.busycdn.xyz
        registerExtractorAPI(SkyBapGoflix())          // goflix.sbs
        registerExtractorAPI(SkyBapGamerxyt())        // gamerxyt.com generator

        registerExtractorAPI(SkyBapHubCloud())
        registerExtractorAPI(SkyBapVCloud())
        registerExtractorAPI(SkyBapGDFlix())
        registerExtractorAPI(SkyBapGDLink())
        registerExtractorAPI(SkyBapGDFlixApp())
        registerExtractorAPI(SkyBapGdFlix1())
        registerExtractorAPI(SkyBapGdFlix2())
        registerExtractorAPI(SkyBapHubdrive())
        registerExtractorAPI(SkyBapDriveleech())
        registerExtractorAPI(SkyBapDriveseed())
        registerExtractorAPI(SkyBapGofile())
        registerExtractorAPI(SkyBapHowblogs())
        registerExtractorAPI(SkyBapHowblogsSub())
        registerExtractorAPI(SkyBapTpead())
        registerExtractorAPI(SkyBapAdvtpe())
    }
}
