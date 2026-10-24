// use an integer for version numbers
version = 1

cloudstream {
    authors = listOf("ChocoCooper")
    description = "Asian Drama & Vivamax"

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
    )
    language = "en"
    iconUrl = "https://t0.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://khflix.com&size=256"

    isCrossPlatform = false
}
