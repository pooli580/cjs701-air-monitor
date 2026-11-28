param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$Aapt2,
    [Parameter(Mandatory)][string]$Output,
    [string]$ApkBaseUrl,
    [string]$ApkUrl,
    [string]$Notes = ''
)

$ErrorActionPreference = 'Stop'
$apkFile = Get-Item -LiteralPath $Apk
if ($apkFile.Length -gt 64MB -or $Notes.Length -gt 2400) { throw 'Update package or notes are too large.' }
$badging = & $Aapt2 dump badging $apkFile.FullName
if ($LASTEXITCODE -ne 0) { throw 'Could not read the APK manifest.' }
$package = $badging | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
$version = [regex]::Match($package, "^package: name='com\.lkwang\.cjs701\.cloud' versionCode='([0-9]+)' versionName='([^']+)'")
if (!$version.Success -or $version.Groups[2].Value.Length -gt 32) { throw 'APK identity or version is invalid.' }
$download = [Uri]::EscapeDataString($apkFile.Name)
if ($ApkUrl -and $ApkBaseUrl) { throw 'Choose either -ApkUrl or -ApkBaseUrl.' }
if ($ApkBaseUrl) {
    $base = [Uri]$ApkBaseUrl
    if (!$base.IsAbsoluteUri -or $base.Scheme -ne 'https' -or !$base.AbsolutePath.EndsWith('/') -or $base.UserInfo -or $base.Query -or $base.Fragment) {
        throw 'APK base URL must be an HTTPS directory ending in /.'
    }
    $download = [Uri]::new($base, $download).AbsoluteUri
}
if ($ApkUrl) {
    $url = [Uri]$ApkUrl
    if (!$url.IsAbsoluteUri -or $url.Scheme -ne 'https' -or $url.UserInfo -or $url.Fragment) {
        throw 'APK URL must be an HTTPS URL.'
    }
    $download = $url.AbsoluteUri
}
$feed = [ordered]@{
    versionCode = [long]$version.Groups[1].Value
    versionName = $version.Groups[2].Value
    apkUrl = $download
    sha256 = (Get-FileHash -LiteralPath $apkFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    notes = $Notes
} | ConvertTo-Json
$feed = $feed.Replace("`r`n", "`n")
[IO.File]::WriteAllText($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output),
    "$feed`n", [Text.UTF8Encoding]::new($false))
