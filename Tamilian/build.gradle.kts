// use an integer for version numbers
version = 4

cloudstream {
    language = "ta"
    // All of these properties are optional, you can safely remove them
    description = "Movies (Tamil)"
    authors = listOf("ChocoCooper")
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    
    // Note: Cloudstream uses singular "Movie", not "Movies"
    tvTypes = listOf("Movie")

    iconUrl = "https://www.google.com/s2/favicons?sz=64&domain=tamilian.io"

    isCrossPlatform = False
}
