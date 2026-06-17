package com.StreamHub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class StreamHubPlugin: Plugin() { 
    override fun load(context: Context) {
        registerMainAPI(StreamHubProvider())
    }
}
