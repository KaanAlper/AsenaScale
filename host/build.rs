//! Compiles the embedded Tailscale node (../tsbridge, Go) into a static C
//! library and links it in, so AsenaScale is one self-contained binary.
//! Needs Go (the toolchain in tsbridge/go.mod is fetched automatically) and a
//! C compiler for the target (mingw-w64 when building for Windows).

use std::env;
use std::path::PathBuf;
use std::process::Command;

fn main() {
    let os = env::var("CARGO_CFG_TARGET_OS").unwrap();
    let arch = env::var("CARGO_CFG_TARGET_ARCH").unwrap();
    let target = env::var("TARGET").unwrap();
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    let bridge = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../tsbridge");

    let goos = match os.as_str() {
        "windows" => "windows",
        "macos" => "darwin",
        _ => "linux",
    };
    let goarch = match arch.as_str() {
        "aarch64" => "arm64",
        _ => "amd64",
    };

    let mut go = Command::new(env::var("GO").unwrap_or_else(|_| "go".into()));
    go.current_dir(&bridge)
        .args(["build", "-trimpath", "-ldflags", "-s -w", "-buildmode=c-archive", "-o"])
        .arg(out.join("libtsbridge.a"))
        .arg("./capi")
        .env("CGO_ENABLED", "1")
        .env("GOOS", goos)
        .env("GOARCH", goarch)
        .env("GOTOOLCHAIN", env::var("GOTOOLCHAIN").unwrap_or_else(|_| "auto".into()));
    // Same C compiler the Rust target uses (e.g. CC_x86_64_pc_windows_gnu).
    let cc_key = format!("CC_{}", target.replace('-', "_"));
    if let Ok(cc) = env::var(&cc_key).or_else(|_| env::var("TARGET_CC")) {
        go.env("CC", cc);
    } else if os == "windows" && !cfg!(windows) {
        go.env("CC", "x86_64-w64-mingw32-gcc");
    }
    let status = go.status().expect("running `go build` for the embedded Tailscale (is Go installed?)");
    assert!(status.success(), "building ../tsbridge/capi failed");

    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-lib=static=tsbridge");
    match os.as_str() {
        "windows" => {
            for lib in [
                "ws2_32", "winmm", "ntdll", "userenv", "bcrypt", "iphlpapi", "dnsapi", "advapi32", "crypt32",
                "ole32", "oleaut32", "netapi32", "secur32", "wintrust", "mswsock", "powrprof", "setupapi",
            ] {
                println!("cargo:rustc-link-lib={lib}");
            }
        }
        "macos" => {
            for fw in ["CoreFoundation", "Security", "SystemConfiguration"] {
                println!("cargo:rustc-link-lib=framework={fw}");
            }
            println!("cargo:rustc-link-lib=resolv");
        }
        _ => {
            println!("cargo:rustc-link-lib=pthread");
            println!("cargo:rustc-link-lib=dl");
            println!("cargo:rustc-link-lib=resolv");
        }
    }
    println!("cargo:rerun-if-changed=../tsbridge");
    println!("cargo:rerun-if-env-changed=GO");
}
