use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let root = manifest_dir.parent().unwrap().parent().unwrap();
    let lib_dir = root.join("native/install/arm64-v8a/lib");
    let deps_dir = root.join("native/deps/install/arm64-v8a/lib");

    println!("cargo:rustc-link-search=native={}", lib_dir.display());
    println!("cargo:rustc-link-search=native={}", deps_dir.display());
    println!("cargo:rustc-link-lib=dylib=obs");
    println!("cargo:rustc-link-lib=dylib=obs-android-plugins");
    println!("cargo:rerun-if-changed=build.rs");
}
