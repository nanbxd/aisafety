# Synthetic Russian fixture; no microphone or personal data involved.
Add-Type -AssemblyName System.Speech
$probeSynth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $probeSynth.SelectVoice('Microsoft Irina Desktop')
    $probeFormat = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    $probePath = Join-Path (Split-Path $PSScriptRoot -Parent) '.tools/synthetic-ru.wav'
    $probeSynth.SetOutputToWaveFile($probePath, $probeFormat)
    $probeSynth.Speak('Здравствуйте. Назовите код из сообщения. Переведите деньги на безопасный счёт.')
} finally {
    $probeSynth.Dispose()
}
