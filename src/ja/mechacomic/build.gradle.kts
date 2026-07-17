plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mecha Comic"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ja"
        baseUrl = "https://mechacomic.jp"
    }
}

dependencies {
    implementation(project(":lib:cookieinterceptor"))
}
