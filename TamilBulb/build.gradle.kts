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
    iconUrl = "https://tamilbulb.cc/favicon.ico"

    isCrossPlatform = false
}
