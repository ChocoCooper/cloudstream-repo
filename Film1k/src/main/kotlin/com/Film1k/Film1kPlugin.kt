package com.film1k

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Film1kPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Film1kProvider())
        registerExtractorAPI(Kwik())
        registerExtractorAPI(Pahe())
        registerExtractorAPI(SuperVideo())
        registerExtractorAPI(Akamaicdn())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixApp())
        registerExtractorAPI(GdFlix1())
        registerExtractorAPI(GdFlix2())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Driveseed())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Howblogs())
        registerExtractorAPI(Wootly())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Videostr())
        registerExtractorAPI(Streameeeeee())
        registerExtractorAPI(PpzjYoutube())
        registerExtractorAPI(Asianload())
        registerExtractorAPI(Embedload())
        registerExtractorAPI(Cloudnestra())
        registerExtractorAPI(Cloudorchestranova())
        registerExtractorAPI(FlixCloud())
        registerExtractorAPI(VibePlayer())
        registerExtractorAPI(Bibiemb())
        registerExtractorAPI(Vivibebe())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(Otakuvid())
        registerExtractorAPI(Otakuhg())
        registerExtractorAPI(Allanimeups())
        registerExtractorAPI(Bysekoze())
        registerExtractorAPI(MegaPlay())
        registerExtractorAPI(Vidtube())
        registerExtractorAPI(Vidwish())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(LuluStream())
    }
}
