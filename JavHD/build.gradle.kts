// use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = ""
    language = "ja"
    authors = listOf("ChocoCooper")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf("NSFW")
    iconUrl = "https://javhd.today/logo/javhd1.png"
    isCrossPlatform = true
}
