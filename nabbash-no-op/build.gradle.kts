plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.amirzregat.nabbash.noop"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    compileOnly(libs.okhttp)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.amirzregat.nabbash"
            artifactId = "nabbash-no-op"
            version = "1.0.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
