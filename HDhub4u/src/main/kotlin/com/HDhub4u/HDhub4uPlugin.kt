package com.hdhub4u

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.app

@CloudstreamPlugin
class HDhub4uPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HDhub4uProvider())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Hubstream())
        registerExtractorAPI(Hubcdnn())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Hubstreamdad())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(PixelDrainDev())
    }

    companion object {
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"

        // NOTE: this is still fetched lazily/suspend-only from within
        // HDhub4uProvider's refreshDomain() — never from a blocking property
        // initializer — so this cache is populated the first time a suspend
        // caller actually asks for it, with no risk of blocking a thread at
        // plugin-load time.
        var cachedDomains: Domains? = null

        suspend fun getDomains(forceRefresh: Boolean = false): Domains? {
            if (cachedDomains == null || forceRefresh) {
                try {
                    cachedDomains = app.get(DOMAINS_URL, timeout = 15000L).parsedSafe<Domains>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    return cachedDomains // keep any previously cached value instead of wiping it
                }
            }
            return cachedDomains
        }

        data class Domains(
            @param:JsonProperty("hubcloud")
            val hubcloud: String,
            @param:JsonProperty("HDHUB4u")
            val HDHUB4u: String,
        )
    }
}
