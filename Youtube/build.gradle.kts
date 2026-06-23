plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.plugin")
}

// Use an integer for version numbers
version = 2

cloudstream {
    name = "YouTube Plugin"
    description = "Watch Youtube in Cloudstream"
    language = "en" 
    authors = listOf("ChocoCooper")

    status = 1 

    tvTypes = listOf("Others", "Live", "TvSeries")
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/09/YouTube_full-color_icon_%282017%29.svg/3840px-YouTube_full-color_icon_%282017%29.svg.png"

    isCrossPlatform = true
}

dependencies {
    val cloudstream by configurations
    val compileOnly by configurations

    cloudstream("com.lagradost:cloudstream3:pre-release")
    compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.24.2")
}
