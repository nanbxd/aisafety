param(
    [ValidateSet('diagnostic', 'whisper')][string]$Mode = 'diagnostic',
    [string]$Model = 'small',
    [string]$Device = 'cpu',
    [string]$Bind = '127.0.0.1',
    [int]$Port = 8000
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not $env:SAFETY_TOKEN) {
    # Respect the same .env file that the server loads, before creating a session secret.
    $configuredToken = & ./.venv/Scripts/python.exe -c "from dotenv import dotenv_values; print(dotenv_values('.env').get('SAFETY_TOKEN') or '')"
    $env:SAFETY_TOKEN = if ($configuredToken) { $configuredToken.Trim() } else { [guid]::NewGuid().ToString('N') }
}
$env:ASR_MODE = $Mode
$env:ASR_MODEL = $Model
$env:ASR_DEVICE = $Device
$env:ASR_COMPUTE_TYPE = if ($Device -eq 'cuda') { 'float16' } else { 'int8' }
Write-Host "Mode: $Mode | local server http://127.0.0.1:$Port"
Write-Host "Enter this session token in the Android app: $env:SAFETY_TOKEN"
Write-Host "WebRTC caller: /rtc?role=caller | receiver: /rtc?role=receiver"
Write-Host "Use the same room and session token on both sides. Keep the Android receiver open."
Write-Host "Android requires PUBLIC_BASE_URL with a trusted public HTTPS/WSS origin."
Write-Host "Local browser development: leave PUBLIC_BASE_URL empty and use http://localhost:$Port/rtc"
& ./.venv/Scripts/python.exe -m uvicorn server.app:app --host $Bind --port $Port --workers 1 --ws-max-size 70000 --ws-max-queue 8 --no-access-log
exit $LASTEXITCODE
