#!/bin/bash
# Roda automaticamente quando o LocalStack fica pronto.
# Um secret por tenant, no mesmo formato JSON dos secrets reais do RDS.
# host=localhost/5433 porque a aplicacao roda fora do Docker (IDE / mvn spring-boot:run).

set -e

criar_secret() {
  awslocal secretsmanager create-secret --region sa-east-1 \
    --name "$1" \
    --secret-string "$2" > /dev/null
  echo "secret criado: $1"
}

criar_secret "workshop/tenant-a" '{"host":"localhost","port":5433,"dbname":"tenant_a","username":"tenant_a_user","password":"senha_a"}'
criar_secret "workshop/tenant-b" '{"host":"localhost","port":5433,"dbname":"tenant_b","username":"tenant_b_user","password":"senha_b"}'
criar_secret "workshop/tenant-c" '{"host":"localhost","port":5433,"dbname":"tenant_c","username":"tenant_c_user","password":"senha_c"}'
