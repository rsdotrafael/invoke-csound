$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$outputDirectory = Join-Path $projectRoot 'out'
$sourceDirectory = Join-Path $projectRoot 'src\main\java\dev\example'
$resourceDirectory = Join-Path $projectRoot 'src\main\resources'

function Find-CsoundLibrary {
    if ($env:CSOUND_LIBRARY_PATH -and (Test-Path -LiteralPath $env:CSOUND_LIBRARY_PATH)) {
        return $env:CSOUND_LIBRARY_PATH
    }

    $csoundCommand = Get-Command csound -ErrorAction SilentlyContinue
    if ($csoundCommand) {
        $besideExecutable = Join-Path (Split-Path $csoundCommand.Source) 'csound64.dll'
        if (Test-Path -LiteralPath $besideExecutable) {
            return $besideExecutable
        }
    }

    $candidates = @(
        'D:\Program Files\Csound7\bin\csound64.dll',
        'C:\Program Files\Csound7\bin\csound64.dll',
        'C:\Program Files\Csound\bin\csound64.dll'
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw 'csound64.dll não foi encontrada. Defina CSOUND_LIBRARY_PATH com o caminho completo da DLL.'
}

Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $sources = Get-ChildItem -LiteralPath $sourceDirectory -Filter '*.java' | Select-Object -ExpandProperty FullName

    & javac --release 22 --add-modules jdk.httpserver -d $outputDirectory $sources
    if ($LASTEXITCODE -ne 0) {
        throw "A compilação falhou com o código $LASTEXITCODE."
    }

    Copy-Item -LiteralPath (Join-Path $resourceDirectory 'index.html') -Destination $outputDirectory -Force
    $csoundLibrary = Find-CsoundLibrary

    Write-Host 'Abra http://localhost:8080 no browser.' -ForegroundColor Cyan
    Write-Host 'Use Ctrl+C para encerrar.' -ForegroundColor DarkGray

    & java --enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver `
        "-Dcsound.library.path=$csoundLibrary" -cp $outputDirectory dev.example.Main
} finally {
    Pop-Location
}
