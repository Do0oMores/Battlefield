param(
    [Parameter(Mandatory=$true)][string]$BFBackpackJar,
    [string]$JavaHome=$env:JAVA_HOME
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend=(Resolve-Path -LiteralPath $BFBackpackJar).Path
$modJar=Join-Path $projectRoot 'build/libs/battlefield-1.0.jar'
$bridgeJar=Join-Path $projectRoot 'bukkit-bridge/target/BattlefieldBridge-1.0.0-SNAPSHOT.jar'
$output=Join-Path $projectRoot 'build/artifact-check'
New-Item -ItemType Directory -Path $output -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach($artifact in @($modJar,$bridgeJar)) {
    $zip=[IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        $entries=@($zip.Entries | ForEach-Object {$_.FullName})
        if($entries | Where-Object {$_ -match '^(org/bukkit/|top/mores/bFBackpack/)|GameTests\.class$|/FakePlayer'}) {throw "Unexpected API/runtime/test classes in $artifact"}
        if($artifact -eq $modJar -and ($entries | Where-Object {$_ -like 'top/mores/battlefield/bukkit/*'})) {throw 'Bukkit implementation leaked into Forge artifact'}
        if($artifact -eq $bridgeJar -and -not ($entries -contains 'plugin.yml')) {throw 'Bridge plugin.yml missing'}
    } finally {$zip.Dispose()}
}
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -proc:none -cp $backend -d $output (Join-Path $PSScriptRoot 'VerifyExampleCatalog.java')
if($LASTEXITCODE -ne 0){throw 'Backend example verifier compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$output;$backend" VerifyExampleCatalog (Join-Path $projectRoot 'integration-examples/bfbackpack')
if($LASTEXITCODE -ne 0){throw 'Example catalog validation failed'}
Write-Output 'PHASE2_ARTIFACT_OK: provided API isolation, no GameTest leakage, backend-validated examples'
