param(
    [ValidateSet('diagnostic', 'gemini')][string]$Mode = 'gemini',
    [string]$Model = 'gemini-3.8-live',
    [string]$Bind = '127.0.0.1',
    [int]$Port = 8000
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if ($PSBoundParameters.ContainsKey('Mode')) { $env:ANALYSIS_MODE = $Mode }
elseif (-not $env:ANALYSIS_MODE) {
    $configuredMode = & ./.venv/Scripts/python.exe -c "from dotenv import dotenv_values; print(dotenv_values('.env').get('ANALYSIS_MODE') or 'gemini')"
    $env:ANALYSIS_MODE = $configuredMode.Trim()
}
if ($PSBoundParameters.ContainsKey('Model')) { $env:GEMINI_MODEL = $Model }
elseif (-not $env:GEMINI_MODEL) {
    $configuredModel = & ./.venv/Scripts/python.exe -c "from dotenv import dotenv_values; print(dotenv_values('.env').get('GEMINI_MODEL') or 'gemini-3.8-live')"
    $env:GEMINI_MODEL = $configuredModel.Trim()
}
Write-Host "Mode: $env:ANALYSIS_MODE | local server http://127.0.0.1:$Port"
Write-Host "Gemini requires a server-only GEMINI_API_KEY. Diagnostic mode disables analysis."
Write-Host "WebRTC caller: /rtc?role=caller | receiver: /rtc?role=receiver"
Write-Host "Use the receiver's phone address on both sides. Keep the Android receiver available."
Write-Host "Android requires PUBLIC_BASE_URL with a trusted public HTTPS/WSS origin."
Write-Host "Local browser development: leave PUBLIC_BASE_URL empty and use http://localhost:$Port/rtc"
& ./.venv/Scripts/python.exe -m uvicorn server.app:app --host $Bind --port $Port --workers 1 --ws-max-size 70000 --ws-max-queue 8 --no-access-log
exit $LASTEXITCODE
