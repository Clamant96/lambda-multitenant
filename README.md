# lambda-multitenant

Exemplo de workshop: **uma unica Lambda, N bancos de dados**, escolhidos em tempo de execucao pelo header
`X-Secret-Id`. Nenhum banco ou secret fica fixo na funcao.

Mesma stack do agilcorp (Java 17, Spring Boot 3.2.7, aws-serverless-java-container, Hikari, JPA, AWS SDK
Secrets Manager), mas **sem layers**: tudo que a Lambda precisa vai dentro do proprio zip.

## Como funciona

```
Requisicao ──> SecretHeaderFilter ──> SecretContextHolder (ThreadLocal) ──> TenantRoutingDataSource
               le X-Secret-Id          guarda o tenant da requisicao        |
               sem header = 400                                             |-- 1o acesso: Secrets Manager -> cria pool Hikari -> cache
                                                                            '-- demais: pool do cache
```

| Arquivo | Papel |
|---|---|
| `config/SecretHeaderFilter.java` | Le o header, valida o tenant (400 se ausente/invalido), limpa o contexto no `finally` |
| `config/SecretContextHolder.java` | `ThreadLocal` com o tenant da requisicao corrente |
| `config/DatabaseConfig.java` | `AbstractRoutingDataSource` + cache de pools + leitura do secret + criacao do schema no 1o acesso |
| `controller/UsuarioController.java` | CRUD comum, **sem nenhuma linha sobre tenant** |
| `controller/TenantController.java` | `GET /tenant/info`: mostra em qual banco a requisicao caiu |

## Rodando a demo local

Pre-requisitos: Docker Desktop, JDK 17, Maven.

```powershell
# 1. Infra: Postgres (3 bancos) + LocalStack (Secrets Manager com 3 secrets)
docker compose down -v; docker compose up -d

# 2. Aplicacao em localhost:8080, com o Secrets Manager apontando para o LocalStack
$env:AWS_ENDPOINT_URL="http://localhost:4566"
mvn spring-boot:run -Plocal

# 3. Roteiro da demo (outro terminal). -Pausar espera ENTER entre os passos
.\demo.ps1 -Pausar
```

Ou use `demo.http` no IntelliJ / VS Code.

| Tenant | Secret | Banco | Estado inicial |
|---|---|---|---|
| A | `workshop/tenant-a` | `tenant_a` | 2 usuarios |
| B | `workshop/tenant-b` | `tenant_b` | 3 usuarios |
| C | `workshop/tenant-c` | `tenant_c` | vazio, tabela criada pela Lambda no 1o acesso |

Cada banco tem usuario/senha proprios: o secret de um tenant nao abre o banco de outro.

## Adicionando um tenant novo (sem deploy)

```powershell
docker exec multitenant-postgres psql -U postgres -c "CREATE USER tenant_d_user WITH PASSWORD 'senha_d'" -c "CREATE DATABASE tenant_d OWNER tenant_d_user"
docker exec multitenant-localstack awslocal secretsmanager create-secret --region sa-east-1 --name workshop/tenant-d --secret-string '{\"host\":\"localhost\",\"port\":5433,\"dbname\":\"tenant_d\",\"username\":\"tenant_d_user\",\"password\":\"senha_d\"}'
```

Depois disso, `X-Secret-Id: workshop/tenant-d` ja funciona, com a aplicacao no ar.

## Deploy na AWS

```powershell
mvn clean package          # gera target/lambda-multitenant-0.0.1-lambda-package.zip (~48 MB, tudo dentro)
sam deploy --guided        # usa o template.yml
```

- Sem `AWS_ENDPOINT_URL`, o client usa o Secrets Manager real da regiao (`AWS_REGION`, padrao `sa-east-1`).
- A role da funcao precisa de `secretsmanager:GetSecretValue` nos secrets dos tenants (ja no `template.yml`).
- O zip fica perto do limite de 50 MB para upload direto no console; acima disso, suba via S3 (o `sam deploy` ja faz isso).

## Pontos de atencao

- **Header e entrada do cliente.** Em producao, restrinja quais secrets podem ser usados (prefixo/allowlist na
  policy IAM e/ou no filtro) e amarre o tenant a identidade autenticada (ex.: claim do JWT).
- **Pool pequeno por tenant** (`maximumPoolSize=2`, `minimumIdle=0`): N tenants x M containers quentes = conexoes
  no banco. Com muitos tenants, use RDS Proxy.
- **1a requisicao de cada tenant por container e mais lenta** (Secrets Manager + conexao + schema). As seguintes
  usam o cache.
- **`SecretContextHolder.clear()` no `finally`** e obrigatorio: o container reaproveita a thread entre invocacoes.
