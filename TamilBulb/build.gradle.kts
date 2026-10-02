// use an integer for version numbers
version = 1


cloudstream {
    authors = listOf("ChocoCooper","Phisher98")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie"
    )
    language = "ta"
    iconUrl = "https://tamilbulb.cc/favicon.ico"

    isCrossPlatform = false
}
