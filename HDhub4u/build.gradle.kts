// use an integer for version numbers
version = 4


cloudstream {
    authors = listOf("ChocoCooper")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )
    description = "HDhub4u Provider"
    iconUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/Icons/HDHUB.png"

    isCrossPlatform = false
}
