<#
.SYNOPSIS
    Packages samples/demo-calculator into a ZIP that TestPilot's upload API accepts.

.DESCRIPTION
    Layout produced (flat archive root):
        pom.xml
        src/main/java/com/example/demo/Calculator.java
        src/main/java/com/example/demo/DataProcessor.java

    This matches ProjectServiceImpl#isRootPom, which requires pom.xml either at the
    archive root or inside exactly one top-level directory.

    Rules honoured by this script:
      * build output (any "target" directory segment) and *.zip are excluded,
        so running "mvn test" inside the sample never leaks into the archive;
      * the sample sources are only read, never written;
      * an existing archive at the destination is overwritten on every run;
      * the resulting archive is NOT tracked by git (*.zip is ignored).

.PARAMETER OutputPath
    Destination ZIP path. Relative paths are resolved against the repository root.
    Defaults to samples/demo-calculator.zip.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/package-sample.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/package-sample.ps1 -OutputPath D:\tmp\demo.zip
#>
[CmdletBinding()]
param(
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Split-Path -Parent $PSScriptRoot
$sampleDir = Join-Path $repoRoot "samples\demo-calculator"

if (-not (Test-Path -LiteralPath $sampleDir -PathType Container)) {
    throw "Sample project not found: $sampleDir"
}
if (-not (Test-Path -LiteralPath (Join-Path $sampleDir "pom.xml") -PathType Leaf)) {
    throw "pom.xml not found in sample project: $sampleDir"
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $zipPath = Join-Path $repoRoot "samples\demo-calculator.zip"
}
else {
    $zipPath = $OutputPath
    if (-not [System.IO.Path]::IsPathRooted($zipPath)) {
        $zipPath = Join-Path $repoRoot $zipPath
    }
}
$zipPath = [System.IO.Path]::GetFullPath($zipPath)
$zipDir = Split-Path -Parent $zipPath

if (-not (Test-Path -LiteralPath $zipDir -PathType Container)) {
    New-Item -ItemType Directory -Path $zipDir -Force | Out-Null
}
if (Test-Path -LiteralPath $zipPath -PathType Leaf) {
    Remove-Item -LiteralPath $zipPath -Force
}

$sampleFull = (Resolve-Path -LiteralPath $sampleDir).ProviderPath.TrimEnd('\', '/')

$files = @(
    Get-ChildItem -LiteralPath $sampleDir -Recurse -File |
        Where-Object {
            $relative = $_.FullName.Substring($sampleFull.Length).TrimStart('\', '/')
            $segments = $relative -split '[\\/]'
            ($segments -notcontains 'target') -and ($_.Extension -ne '.zip')
        } |
        Sort-Object FullName
)

if ($files.Count -eq 0) {
    throw "No files found under $sampleDir"
}

$archive = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($sampleFull.Length).TrimStart('\', '/')
        $entryName = $relative -replace '\\', '/'
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive, $file.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal)
    }
}
finally {
    $archive.Dispose()
}

# Self-check: pom.xml must be at the archive root for the upload API to accept it.
$verifyArchive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $entryNames = @($verifyArchive.Entries | ForEach-Object { $_.FullName })
}
finally {
    $verifyArchive.Dispose()
}

if ($entryNames -notcontains 'pom.xml') {
    throw "Generated archive has no root pom.xml: $zipPath"
}

$zipInfo = Get-Item -LiteralPath $zipPath

Write-Host ""
Write-Host "ZIP created:"
Write-Host ("  Path : " + $zipInfo.FullName)
Write-Host ("  Size : " + $zipInfo.Length + " bytes")
Write-Host ("  Files: " + $entryNames.Count)
Write-Host ""
Write-Host "Entries:"
foreach ($entryName in $entryNames) {
    Write-Host ("  " + $entryName)
}
