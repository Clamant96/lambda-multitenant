package com.a5solutions.lambdamultitenant.controller;

import com.a5solutions.lambdamultitenant.config.DatabaseConfig;
import com.a5solutions.lambdamultitenant.config.SecretContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint didatico: mostra em qual banco a requisicao caiu.
 * Mesma URL, header diferente -> banco diferente.
 */
@RestController
@RequestMapping("/tenant")
public class TenantController {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseConfig.TenantRoutingDataSource routingDataSource;

    public TenantController(JdbcTemplate jdbcTemplate, DatabaseConfig.TenantRoutingDataSource routingDataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.routingDataSource = routingDataSource;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> banco = jdbcTemplate.queryForMap(
                "SELECT current_database() AS banco, current_user AS usuario_banco, current_schema() AS schema");

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("secretId", SecretContextHolder.getSecretId());
        resposta.putAll(banco);
        resposta.put("totalUsuarios", jdbcTemplate.queryForObject("SELECT count(*) FROM usuario", Long.class));
        resposta.put("tenantsEmCacheNesteContainer", routingDataSource.totalTenantsEmCache());
        return resposta;
    }
}
