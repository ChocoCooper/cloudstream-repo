// use an integer for version numbers
version = 3


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
        "Movie"
    )
    language = "ta"
    iconUrl = "https://www.1tamilmv.capital/uploads/monthly_2026_04/logo.png.a2fe3a46dd23c8b4b3798642925294b9.png"

    isCrossPlatform = false
}
