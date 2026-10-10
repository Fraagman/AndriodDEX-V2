@echo off
rem Builds the RELEASE receiver and runs it. The debug build is 3-5x slower
rem (unoptimized rustls + openh264 + wgpu) and measures "the app" wrong.
cd /d "%~dp0..\rust-receiver"
cargo run --release -p zc-core
if errorlevel 1 (
    echo.
    echo Receiver exited with an error. Check the output above.
)
pause
