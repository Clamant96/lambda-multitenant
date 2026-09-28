# Sobe o ambiente completo da demo: Docker (Postgres + LocalStack) e a aplicacao em localhost:8080.
#
# Uso:
#   .\iniciar.ps1           sobe tudo (mantem os dados atuais dos bancos)
#   .\iniciar.ps1 -Zerar    recria os bancos do zero antes de subir (use antes de apresentar)
#   .\iniciar.ps1 -Parar    derruba os containers da demo
#
# A aplicacao roda neste terminal: Ctrl+C para parar.
param([switch]$Zerar, [switch]$Parar)

# Continue (e nao Stop): docker/java escrevem em stderr normalmente, e no Windows PowerShell 5.1
# isso viraria erro fatal. Cada passo abaixo confere o proprio resultado.
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

function Info($msg) { Write-Host ">> $msg" -ForegroundColor Cyan }
function Falha($msg) { Write-Host "ERRO: $msg" -ForegroundColor Red; exit 1 }

if ($Parar) {
    Info "Derrubando os containers da demo"
    docker compose down
    exit 0
}

# ---------- 1. Docker Desktop ----------
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $dockerDesktop = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    if (-not (Test-Path $dockerDesktop)) { Falha "Docker nao esta rodando e o Docker Desktop nao foi encontrado." }
    Info "Abrindo o Docker Desktop (pode levar ~1 min)"
    Start-Process $dockerDesktop
    $limite = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep 3
        docker info *> $null
    } while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $limite)
    if ($LASTEXITCODE -ne 0) { Falha "Docker Desktop nao ficou pronto em 3 minutos." }
}

# ---------- 2. Containers ----------
if ($Zerar) {
    Info "Zerando os bancos (docker compose down -v)"
    docker compose down -v
}
Info "Subindo Postgres (5433) e LocalStack (4566)"
docker compose up -d
if ($LASTEXITCODE -ne 0) { Falha "docker compose up falhou." }

Info "Aguardando Postgres e os secrets do LocalStack"
$limite = (Get-Date).AddMinutes(2)
do {
    Start-Sleep 2
    $pgOk = (docker inspect -f "{{.State.Health.Status}}" multitenant-postgres 2>$null) -eq "healthy"
    $secretsOk = [bool](docker logs multitenant-localstack 2>&1 | Select-String "secret criado: workshop/tenant-c")
} while (-not ($pgOk -and $secretsOk) -and (Get-Date) -lt $limite)
if (-not $pgOk) { Falha "Postgres nao ficou saudavel. Veja: docker logs multitenant-postgres" }
if (-not $secretsOk) { Falha "Secrets nao foram criados. Veja: docker logs multitenant-localstack" }

# ---------- 3. Porta 8080 livre ----------
$ocupante = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($ocupante) {
    $proc = Get-Process -Id $ocupante.OwningProcess -ErrorAction SilentlyContinue
    Falha "A porta 8080 ja esta em uso por '$($proc.ProcessName)' (PID $($ocupante.OwningProcess)). Pare esse processo e rode de novo."
}

# ---------- 4. JDK 17 ----------
function Versao-Java($javaHome) {
    $java = Join-Path $javaHome "bin\java.exe"
    if (-not (Test-Path $java)) { return 0 }
    $saida = & $java -version 2>&1 | Out-String
    if ($saida -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}
$candidatos = @()
if ($env:JAVA_HOME) { $candidatos += $env:JAVA_HOME }
$candidatos += Get-ChildItem "$env:USERPROFILE\.jdks", "C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match "17" } | ForEach-Object FullName
# Prefere exatamente o 17 (mesma versao do runtime da Lambda); outro >= 17 so como ultimo recurso.
$jdk = $candidatos | Where-Object { (Versao-Java $_) -eq 17 } | Select-Object -First 1
if (-not $jdk) { $jdk = $candidatos | Where-Object { (Versao-Java $_) -ge 17 } | Select-Object -First 1 }
if (-not $jdk) { Falha "JDK 17 nao encontrado. Instale um JDK 17 ou defina JAVA_HOME." }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
Info "JDK: $jdk"

# ---------- 5. Maven ----------
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) {
    $mvn = Get-ChildItem "C:\Program Files\JetBrains\*\plugins\maven\lib\maven3\bin\mvn.cmd" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $mvn) { Falha "Maven nao encontrado (nem no PATH, nem dentro da IntelliJ)." }
Info "Maven: $mvn"

# ---------- 6. Aplicacao ----------
$env:AWS_ENDPOINT_URL = "http://localhost:4566"
Write-Host ""
Write-Host "Aplicacao subindo em http://localhost:8080  (Ctrl+C para parar)" -ForegroundColor Green
Write-Host "Header obrigatorio: X-Secret-Id = workshop/tenant-a | workshop/tenant-b | workshop/tenant-c" -ForegroundColor Green
Write-Host ""
& $mvn -q spring-boot:run -Plocal
