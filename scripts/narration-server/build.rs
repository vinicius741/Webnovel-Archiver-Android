// Links the sherpa-onnx C API (a prebuilt shared library). SHERPA_ONNX_LIB_DIR points at its `lib`
// folder; setup.sh downloads it. At run time the binary looks for it in `../lib` next to itself.
fn main() {
    println!("cargo:rerun-if-env-changed=SHERPA_ONNX_LIB_DIR");
    let dir = std::env::var("SHERPA_ONNX_LIB_DIR").unwrap_or_else(|_| "lib".to_string());
    println!("cargo:rustc-link-search=native={dir}");
    println!("cargo:rustc-link-lib=dylib=sherpa-onnx-c-api");
    let target = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target == "macos" {
        println!("cargo:rustc-link-arg=-Wl,-rpath,@executable_path/../lib");
    } else {
        println!("cargo:rustc-link-arg=-Wl,-rpath,$ORIGIN/../lib");
    }
    // Also the build-time folder, so `cargo run` works from a checkout.
    if let Ok(absolute) = std::fs::canonicalize(&dir) {
        println!("cargo:rustc-link-arg=-Wl,-rpath,{}", absolute.display());
    }
}
