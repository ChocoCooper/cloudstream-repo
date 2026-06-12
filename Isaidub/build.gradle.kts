// use an integer for version numbers
version = 26

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Tamil Dubbed Movies"
    language = "ta"
    authors = listOf("ChocoCooper")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of available types here:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf(
        "Movie",
    )
    iconUrl = "https://play-lh.googleusercontent.com/s8kgwZMlmuCW8rje8e6l5ypkBjW16VVKUCy4StPkbjhZOgbTv7P5YetFFpsZHWGeOX6n=w480-h960-rw"
    isCrossPlatform = true
}
