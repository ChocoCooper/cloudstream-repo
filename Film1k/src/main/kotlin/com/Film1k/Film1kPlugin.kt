package com.film1k

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class Film1kPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Film1kProvider())
    }
}
