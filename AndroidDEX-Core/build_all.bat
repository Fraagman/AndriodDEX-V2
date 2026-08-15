@echo off
echo === AndroidDEX Full System Build ===

echo [1/3] Rust Code Quality Checks...
cd %~dp0
cargo fmt --all -- --check
if %ERRORLEVEL% neq 0 (
    echo Rust formatting failed! Run 'cargo fmt' to fix.
    exit /b %ERRORLEVEL%
)

cargo clippy --all-targets --all-features -- -D warnings
if %ERRORLEVEL% neq 0 (
    echo Rust linting failed!
    exit /b %ERRORLEVEL%
)

echo [2/3] Building Rust Workspace (Release)...
cargo build --release
if %ERRORLEVEL% neq 0 (
    echo Rust build failed!
    exit /b %ERRORLEVEL%
)
echo Rust build successful! Executable is at target\release\receiver.exe

echo [3/3] Building Android Application (Debug & Benchmark variants)...
call gradlew clean
call gradlew lint
call gradlew assembleDebug
if %ERRORLEVEL% neq 0 (
    echo Android build failed!
    exit /b %ERRORLEVEL%
)
echo Android build successful!

echo === Build Complete ===
exit /b 0
