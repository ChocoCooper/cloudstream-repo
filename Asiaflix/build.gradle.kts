// use an integer for version numbers
version = 1


cloudstream {
    authors = listOf("ChocoCooper")
    description = "Asian Drama Provider"

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "AsianDrama"
        "TvSeries"
        "Movie"
    )
    language = "en"
    iconUrl = "https://asiaflix.net/assets/main_asiaflix_logo.png"

    isCrossPlatform = false
}
