package com.Film1k

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Film1kPlugin : Plugin() {
    override fun load(context: Context) {
        // Main provider
        registerMainAPI(Film1kProvider())

        // Extractors
        registerExtractorAPI(Film1kExtractor())       // Option 1 — film1k.xyz
        registerExtractorAPI(TurboVidHLSExtractor())  // Option 3 — turbovidhls.com
        registerExtractorAPI(HgCloudExtractor())      // Option 4 — hgcloud.to
        // Option 2 (abyssplayer.com) intentionally not registered
    }
}
