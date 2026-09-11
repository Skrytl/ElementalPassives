$ErrorActionPreference = "Stop"

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "    Building ElementalPassives Minecraft Plugin   " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# Check for javac & jar
if (-not (Get-Command "javac" -ErrorAction SilentlyContinue)) {
    Write-Error "javac command not found. Ensure JDK 17+ or 21+ is installed."
    exit 1
}

$WorkDir = $PSScriptRoot
if (-not $WorkDir) { $WorkDir = Get-Location }

$LibDir = Join-Path $WorkDir ".lib"
$BinDir = Join-Path $WorkDir "bin"
$OutputJar = Join-Path $WorkDir "ElementalPassives.jar"

if (-not (Test-Path $LibDir)) { New-Item -ItemType Directory -Path $LibDir | Out-Null }
if (-not (Test-Path $BinDir)) { New-Item -ItemType Directory -Path $BinDir | Out-Null }

$FullApiJar = Join-Path $LibDir "spigot-bundled.jar"

# Download the complete bundled Spigot API with Keyed and Bukkit internals
if (-not (Test-Path $FullApiJar)) {
    Write-Host "Downloading bundled Spigot library (includes Keyed and Bukkit interfaces)..." -ForegroundColor Yellow
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $downloadUrl = "https://hub.spigotmc.org/nexus/content/groups/public/org/spigotmc/spigot-api/1.20.4-R0.1-20240416.085023-95/spigot-api-1.20.4-R0.1-20240416.085023-95-shaded.jar"
    
    try {
        Invoke-WebRequest -Uri $downloadUrl -OutFile $FullApiJar -UseBasicParsing
    }
    catch {
        # Fallback to Paper's full bundled dev artifact
        $fallbackUrl = "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/1.20.4-R0.1-SNAPSHOT/paper-api-1.20.4-R0.1-20240416.143644-428.jar"
        Invoke-WebRequest -Uri $fallbackUrl -OutFile $FullApiJar -UseBasicParsing
    }
    Write-Host "Library downloaded." -ForegroundColor Green
}

# Clear old compilation output
Get-ChildItem -Path $BinDir -Recurse | Remove-Item -Force -Recurse -ErrorAction SilentlyContinue

Write-Host "Compiling Java source..." -ForegroundColor Yellow
$javaSource = Join-Path $WorkDir "src\main\java\com\elementalpassives\ElementalPassives.java"

& javac -encoding UTF-8 -cp "$FullApiJar;." -d $BinDir $javaSource
if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation halted due to javac errors."
    exit 1
}

Write-Host "Copying plugin.yml..." -ForegroundColor Yellow
$pluginYml = Join-Path $WorkDir "src\main\resources\plugin.yml"
Copy-Item $pluginYml -Destination (Join-Path $BinDir "plugin.yml")

Write-Host "Packaging into ElementalPassives.jar..." -ForegroundColor Yellow
Push-Location $BinDir
try {
    & jar cvf $OutputJar .
} finally {
    Pop-Location
}

Write-Host "==================================================" -ForegroundColor Green
Write-Host "SUCCESS! ElementalPassives.jar has been generated!" -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Green