package com.tamilmv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TamilMVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TamilMVProvider())
    }
}
