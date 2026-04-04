# Allow script execution for this PowerShell session only
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force

# clean-and-build-debug.ps1
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force

Write-Host "Cleaning Android project..." -ForegroundColor Cyan

.\gradlew --stop | Out-Null
taskkill /F /IM java.exe /T 2>$null
taskkill /F /IM gradle.exe /T 2>$null

Start-Sleep -Seconds 2

Remove-Item .\app\build -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "Starting Android Debug build..." -ForegroundColor Cyan

# Ensure we're in an Android project root
if (-not (Test-Path ".\gradlew")) {
    Write-Error "gradlew not found. Make sure you are in the Android project root."
    exit 1
}

# Clean and build Debug APK
.\gradlew clean assembleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Error "Android Debug build failed."
    exit 1
}

Write-Host "Android Debug build completed successfully." -ForegroundColor Green
Write-Host "APK location: app\build\outputs\apk\debug\" -ForegroundColor Yellow
