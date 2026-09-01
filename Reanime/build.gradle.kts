// use an integer for version numbers
version = 1

cloudstream {
    language = "en"
    authors = listOf("ChocoCooper")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://reanime.to/favicon.ico"
    isCrossPlatform = true
}

dependencies {
    // Pure JVM WebAssembly runtime – version known to exist on Maven Central
    implementation("com.dylibso.chicory:runtime:0.0.10")
}
