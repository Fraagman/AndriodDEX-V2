$ErrorActionPreference = "Stop"

# Paths
$WORKSPACE_DIR = (Resolve-Path "$PSScriptRoot\..").Path
$RECEIVER_DIR = "$WORKSPACE_DIR\rust-receiver"
$PACKAGING_DIR = "$WORKSPACE_DIR\packaging\windows"
$RELEASE_DIR = "$WORKSPACE_DIR\release"
$MSIX_PATH = "$RELEASE_DIR\androiddex.msix"

# Create directories
New-Item -ItemType Directory -Force -Path "$PACKAGING_DIR\Assets" | Out-Null
New-Item -ItemType Directory -Force -Path $RELEASE_DIR | Out-Null

# Create dummy assets for the appx
function Create-DummyImage {
    param ($Path, $Width, $Height)
    if (-not (Test-Path $Path)) {
        # Using a simple script to generate an image
        Add-Type -AssemblyName System.Drawing
        $bmp = New-Object System.Drawing.Bitmap $Width, $Height
        $graphics = [System.Drawing.Graphics]::FromImage($bmp)
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Blue)
        $graphics.FillRectangle($brush, 0, 0, $Width, $Height)
        $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
        $graphics.Dispose()
        $bmp.Dispose()
    }
}
Create-DummyImage "$PACKAGING_DIR\Assets\StoreLogo.png" 50 50
Create-DummyImage "$PACKAGING_DIR\Assets\Square150x150Logo.png" 150 150
Create-DummyImage "$PACKAGING_DIR\Assets\Square44x44Logo.png" 44 44
Create-DummyImage "$PACKAGING_DIR\Assets\Wide310x150Logo.png" 310 150
Create-DummyImage "$PACKAGING_DIR\Assets\SplashScreen.png" 620 300

# Step 1: cargo build
Write-Host "Building zc-core in release mode..."
Push-Location "$RECEIVER_DIR\zc-core"
cargo build --release --target x86_64-pc-windows-msvc
if ($LASTEXITCODE -ne 0) {
    Write-Error "Cargo build failed"
}
Pop-Location

# Step 2: Copy exe
Write-Host "Copying executable..."
Copy-Item "$RECEIVER_DIR\target\x86_64-pc-windows-msvc\release\zc-core.exe" "$PACKAGING_DIR\androiddex.exe" -Force

# Find makeappx.exe and signtool.exe
$MakeAppxPath = (Get-Command "makeappx.exe" -ErrorAction Ignore).Source
$SignToolPath = (Get-Command "signtool.exe" -ErrorAction Ignore).Source

if (-not $MakeAppxPath) {
    $SdkPaths = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin\*\x64\makeappx.exe" -ErrorAction Ignore | Sort-Object FullName
    if ($SdkPaths) { $MakeAppxPath = $SdkPaths[-1].FullName }
}

if (-not $SignToolPath) {
    $SdkPaths = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin\*\x64\signtool.exe" -ErrorAction Ignore | Sort-Object FullName
    if ($SdkPaths) { $SignToolPath = $SdkPaths[-1].FullName }
}

if (-not $MakeAppxPath -or -not $SignToolPath) {
    Write-Host "WARNING: Windows SDK tools (makeappx.exe, signtool.exe) not found in PATH or standard directories." -ForegroundColor Yellow
    Write-Host "Please install the Windows SDK: https://developer.microsoft.com/en-us/windows/downloads/windows-sdk/" -ForegroundColor Yellow
    Write-Host "Or open a 'Developer Command Prompt for VS'." -ForegroundColor Yellow
    exit 1
}

# Step 3: Pack
Write-Host "Packaging MSIX..."
if (Test-Path $MSIX_PATH) { Remove-Item $MSIX_PATH -Force }
& $MakeAppxPath pack /d $PACKAGING_DIR /p $MSIX_PATH /o

# Step 4: Sign
# Instructions say: If signtool.exe available: signtool sign /fd sha256 /a androiddex.msix
Write-Host "Signing MSIX..."
& $SignToolPath sign /fd sha256 /a $MSIX_PATH
if ($LASTEXITCODE -ne 0) {
    Write-Host "SignTool failed to sign automatically. You might need to specify a certificate manually." -ForegroundColor Yellow
}

Write-Host "Successfully built and packaged MSIX: $MSIX_PATH" -ForegroundColor Green
