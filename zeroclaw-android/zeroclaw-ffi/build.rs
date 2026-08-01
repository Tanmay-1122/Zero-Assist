fn main() {
    println!("cargo:rerun-if-env-changed=TARGET");

    let target = std::env::var("TARGET").unwrap_or_default();

    if target.contains("linux-android") {
        // Google Play requires 16 KB page alignment for Android native libraries.
        println!("cargo:rustc-link-arg-cdylib=-Wl,-z,max-page-size=16384");
    }
}
