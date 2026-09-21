package com.skybap

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SkyBapPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SkyBapProvider())

        // Direct-CDN extractors solved from live recon.
        registerExtractorAPI(SkyBapPixeldrain())      // pixeldrain.com / pixeldrain.dev
        registerExtractorAPI(SkyBapPixelHubcloud())   // pixel.hubcloud.ist  (10 Gbps direct)
        registerExtractorAPI(SkyBapBusyCdn())         // instant.busycdn.xyz (signed direct)
        registerExtractorAPI(SkyBapGoflix())          // goflix.sbs  (mirror + /download-fast/…)
        registerExtractorAPI(SkyBapGamerxyt())        // gamerxyt.com/hubcloud.php generator

        // Panel / file-host extractors.
        registerExtractorAPI(SkyBapHubCloud())        // https://hubcloud.*  (+ /drive/)
        registerExtractorAPI(SkyBapVCloud())          // https://vcloud.*
        registerExtractorAPI(SkyBapGDFlix())          // https://gdflix.*
        registerExtractorAPI(SkyBapGDLink())          // https://gdlink.*
        registerExtractorAPI(SkyBapGDFlixApp())       // https://new.gdflix.*
        registerExtractorAPI(SkyBapGdFlix1())         // https://new1.gdflix.*
        registerExtractorAPI(SkyBapGdFlix2())         // https://*.gdflix.*  (rotating subdomains)
        registerExtractorAPI(SkyBapHubdrive())        // https://hubdrive.*
        registerExtractorAPI(SkyBapDriveleech())      // https://driveleech.*
        registerExtractorAPI(SkyBapDriveseed())       // https://driveseed.*
        registerExtractorAPI(SkyBapGofile())          // https://gofile.io
        registerExtractorAPI(SkyBapHowblogs())        // https://howblogs.*  (+ *.howblogs.*)
        registerExtractorAPI(SkyBapTpead())           // https://tpead.net
        registerExtractorAPI(SkyBapAdvtpe())          // https://advtpe.*
    }
}
