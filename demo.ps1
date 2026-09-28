# Roteiro da demo ao vivo. Pre-requisito: docker compose up -d  +  aplicacao rodando em localhost:8080
# Uso: .\demo.ps1            (roda tudo)
#      .\demo.ps1 -Pausar    (espera ENTER entre os passos, para apresentar)
param([switch]$Pausar, [string]$BaseUrl = "http://localhost:8080")

function Passo($titulo) {
    Write-Host ""
    Write-Host "=== $titulo ===" -ForegroundColor Cyan
    if ($Pausar) { Read-Host "ENTER para executar" | Out-Null }
}

function Chamar($metodo, $caminho, $secretId, $corpo) {
    $headers = @{}
    if ($secretId) { $headers["X-Secret-Id"] = $secretId }
    Write-Host "$metodo $caminho   X-Secret-Id: $(if ($secretId) { $secretId } else { '(ausente)' })" -ForegroundColor DarkGray
    try {
        $params = @{ Method = $metodo; Uri = "$BaseUrl$caminho"; Headers = $headers; ContentType = "application/json" }
        if ($corpo) { $params.Body = ($corpo | ConvertTo-Json) }
        $resp = Invoke-RestMethod @params
        $resp | ConvertTo-Json -Depth 5
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        Write-Host "HTTP $status  $($_.ErrorDetails.Message)" -ForegroundColor Yellow
    }
}

Passo "1. Mesma URL, tenant A"
Chamar GET "/tenant/info" "workshop/tenant-a"

Passo "2. Mesma URL, so troquei o header -> tenant B"
Chamar GET "/tenant/info" "workshop/tenant-b"

Passo "3. Tenant C: banco vazio, tabela criada pela Lambda no 1o acesso"
Chamar GET "/tenant/info" "workshop/tenant-c"

Passo "4. Listar usuarios de A e de B (dados isolados)"
Chamar GET "/usuario" "workshop/tenant-a"
Chamar GET "/usuario" "workshop/tenant-b"

Passo "5. Cadastrar usuario SO no tenant C"
Chamar POST "/usuario" "workshop/tenant-c" @{ usuario = "novo.cliente-c"; email = "novo@cliente-c.com"; senha = "123456" }
Chamar GET "/usuario" "workshop/tenant-c"
Chamar GET "/usuario" "workshop/tenant-a"

Passo "6. Sem header -> 400 (fail-closed, nao cai num banco padrao)"
Chamar GET "/usuario" $null

Passo "7. Secret que nao existe -> 400"
Chamar GET "/usuario" "workshop/tenant-x"
