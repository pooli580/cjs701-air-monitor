param(
    [Parameter(Mandatory)][string]$Jdk,
    [Parameter(Mandatory)][string]$Platform,
    [Parameter(Mandatory)][string]$BuildTools,
    [Parameter(Mandatory)][string]$MqttJar,
    [Parameter(Mandatory)][string]$KeyStore,
    [Parameter(Mandatory)][string]$OutputApk,
    [string]$DashboardPage = (Join-Path $PSScriptRoot '../dashboard/index.html')
)

$ErrorActionPreference = 'Stop'
if (!$env:CJS701_SIGNING_PASSWORD) {
    throw 'Set CJS701_SIGNING_PASSWORD in the build process.'
}

function Invoke-BuildTool {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE) {
        throw "Build tool exited with ${LASTEXITCODE}: $Executable"
    }
}

$build = New-Item -Path (Join-Path $env:TEMP ('cjs701-apk-' + [Guid]::NewGuid().ToString('N'))) -ItemType Directory
$work = $build.FullName
$generated = (New-Item -Path (Join-Path $work 'generated') -ItemType Directory).FullName
$assets = (New-Item -Path (Join-Path $work 'assets') -ItemType Directory).FullName
Copy-Item -LiteralPath $DashboardPage -Destination (Join-Path $assets 'md1000-monitor.html')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../LICENSE'),(Join-Path $PSScriptRoot '../NOTICE') -Destination $assets
$classes = (New-Item -Path (Join-Path $work 'classes') -ItemType Directory).FullName
$dex = (New-Item -Path (Join-Path $work 'dex') -ItemType Directory).FullName
$platformJar = Join-Path $Platform 'android.jar'
$resources = Join-Path $work 'resources.zip'
$unsignedApk = Join-Path $work 'unsigned.apk'
$alignedApk = Join-Path $work 'aligned.apk'
$classesJar = Join-Path $work 'classes.jar'

$aapt = Join-Path $BuildTools 'aapt2.exe'
$java = Join-Path $Jdk 'bin/java.exe'
Invoke-BuildTool $aapt @('compile', '--dir', (Join-Path $PSScriptRoot 'res'), '-o', $resources)
Invoke-BuildTool $aapt @('link', '-I', $platformJar, '--manifest', (Join-Path $PSScriptRoot 'AndroidManifest.xml'),
    '--java', $generated, '--custom-package', 'com.lkwang.cjs701', '-A', $assets, '-o', $unsignedApk, $resources)

$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src'),$generated -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName)
Invoke-BuildTool (Join-Path $Jdk 'bin/javac.exe') (@('-encoding', 'UTF-8', '--release', '8',
    '-classpath', "$platformJar;$MqttJar", '-d', $classes) + $sources)
Invoke-BuildTool (Join-Path $Jdk 'bin/jar.exe') @('--create', '--file', $classesJar, '-C', $classes, '.')
Invoke-BuildTool $java @('-cp', (Join-Path $BuildTools 'lib/d8.jar'), 'com.android.tools.r8.D8',
    '--min-api', '26', '--lib', $platformJar, '--output', $dex, $classesJar, $MqttJar)
Invoke-BuildTool (Join-Path $Jdk 'bin/jar.exe') @('--update', '--file', $unsignedApk, '-C', $dex, 'classes.dex')

$mqttResources = (New-Item -Path (Join-Path $work 'mqtt-resources') -ItemType Directory).FullName
$jar = Join-Path $Jdk 'bin/jar.exe'
Push-Location -LiteralPath $mqttResources
try {
    Invoke-BuildTool $jar @('--extract', '--file', $MqttJar,
        'META-INF/services/org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory',
        'org/eclipse/paho/client/mqttv3/internal/nls', 'org/eclipse/paho/client/mqttv3/logging/jsr47min.properties',
        'about.html')
    $resourcesToAdd = @('META-INF/services', 'org')
    if (Test-Path -LiteralPath 'about.html') { $resourcesToAdd += 'about.html' }
    Invoke-BuildTool $jar (@('--update', '--file', $unsignedApk) + $resourcesToAdd)
} finally {
    Pop-Location
}

$zipalign = Join-Path $BuildTools 'zipalign.exe'
Invoke-BuildTool $zipalign @('-p', '4', $unsignedApk, $alignedApk)
$signer = Join-Path $BuildTools 'lib/apksigner.jar'
Invoke-BuildTool $java @('-jar', $signer, 'sign', '--ks', $KeyStore, '--ks-key-alias', 'cjs701',
    '--ks-pass', 'env:CJS701_SIGNING_PASSWORD', '--out', $OutputApk, $alignedApk)
Invoke-BuildTool $zipalign @('-c', '-p', '4', $OutputApk)
Invoke-BuildTool $java @('-jar', $signer, 'verify', '--verbose', '--print-certs', $OutputApk)
