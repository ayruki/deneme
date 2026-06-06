dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 100086

cloudstream {
    description = "Izlelan TMDB-powered Cloudstream extension with support for various video sources."
    authors = listOf("Antigravity")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon")
    requiresResources = false
    language = "tr"
    iconUrl = "https://raw.githubusercontent.com/Kraptor123/Cs-Karma/master/icon.png"
}

android {
    namespace = "com.izlelan"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
