$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

Push-Location (Join-Path $ProjectRoot "core")
try {
    rustup target add armv7-linux-androideabi aarch64-linux-android
    if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
        cargo install cargo-ndk
    }
    cargo ndk `
        -t armeabi-v7a `
        -t arm64-v8a `
        -o (Join-Path $ProjectRoot "app/src/main/jniLibs") `
        build --release
} finally {
    Pop-Location
}

Write-Host "Native libraries written for armeabi-v7a and arm64-v8a."
