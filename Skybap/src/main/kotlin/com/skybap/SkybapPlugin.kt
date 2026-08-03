package com.skybap

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SkyBapPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SkyBapProvider())

        // Non-core hosts used by SkyBap's download/watch links. Once
        // registered here, plain loadExtractor() calls anywhere (including
        // inside SkyBapHowblogs itself) will route to these automatically.
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
    }
}
