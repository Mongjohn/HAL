buildscript {
    dependencies {
        constraints {
            add("classpath", "com.google.guava:guava:33.7.2-jre") {
                because("GHSA-xxph-c9ww-hj94; build tools use the JRE variant independently of HAL's Android runtime")
            }
            add("classpath", "org.apache.commons:commons-lang3:3.18.0") {
                because("GHSA-j288-q9x7-2f5v fixes unbounded recursion")
            }
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6") {
                because("GHSA-3677-xxcr-wjqv bounds compressed JWE handling in bundle tooling")
            }
            add("classpath", "org.jdom:jdom2:2.0.6.1") {
                because("GHSA-2363-cqg2-863c hardens XML parsing in Jetifier tooling")
            }
            for (module in listOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on")) {
                add("classpath", "org.bouncycastle:$module:1.85") {
                    because("Keep the BC family aligned; fixes GHSA-wg6q-6289-32hp, GHSA-9pwp-9qqc-pr26 and GHSA-qp49-qgx5-5m26")
                }
            }
        }
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
