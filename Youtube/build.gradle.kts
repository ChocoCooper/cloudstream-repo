// Use an integer for version numbers
version = 2

cloudstream {
    description = "Watch Youtube in Cloudstream"
    language = "en" // FIX: Matched to English so it shows up properly in the app
    authors = listOf("ChocoCooper")

    status = 1 

    // FIX: Changed "Other" to "Others" to match Cloudstream's TvType exactly
    tvTypes = listOf("Others", "Live", "TvSeries")
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/09/YouTube_full-color_icon_%282017%29.svg/3840px-YouTube_full-color_icon_%282017%29.svg.png"

    isCrossPlatform = true
}
