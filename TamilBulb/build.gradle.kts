// use an integer for version numbers
version = 1


cloudstream {
    authors = listOf("ChocoCooper")
    description = "TamilBulb Provider"
    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 2 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie"
    )
    language = "ta"
    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://tamilbulb.cc&size=256"

    isCrossPlatform = false
}
