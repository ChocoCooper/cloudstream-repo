// use an integer for version numbers
version = 1


cloudstream {
    authors = listOf("ChocoCooper")
    description = "TamilMV Provider (only contents with DL)"

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "TvSeries",
        "AsianDrama"
    )
    language = "en"
    iconUrl = "https://narto-drama.com/narto-drama-logo-compressed.png"

    isCrossPlatform = false
}
