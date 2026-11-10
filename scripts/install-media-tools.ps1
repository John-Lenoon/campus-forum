$ErrorActionPreference = 'Stop'
$forumRoot = Split-Path -Parent $PSScriptRoot
$forumTools = Join-Path $forumRoot '.tools'
New-Item -ItemType Directory -Path $forumTools -Force | Out-Null
$forumArchive = Join-Path $forumTools 'ffmpeg.zip'
$forumDownload = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'
Invoke-WebRequest -Uri $forumDownload -OutFile $forumArchive
$forumExpected = [regex]::Match([string](Invoke-RestMethod -Uri ($forumDownload + '.sha256')), '[a-fA-F0-9]{64}').Value
if (-not $forumExpected -or (Get-FileHash -LiteralPath $forumArchive -Algorithm SHA256).Hash -ne $forumExpected) {
    throw 'FFmpeg archive checksum mismatch'
}
Expand-Archive -LiteralPath $forumArchive -DestinationPath $forumTools -Force
$forumExecutable = Get-ChildItem -LiteralPath $forumTools -Recurse -Filter ffmpeg.exe | Select-Object -First 1
if (-not $forumExecutable) { throw 'FFmpeg executable missing' }
$env:FFMPEG_PATH = $forumExecutable.FullName
Write-Output ('FFMPEG_PATH=' + $env:FFMPEG_PATH)
& $env:FFMPEG_PATH -version | Select-Object -First 1
