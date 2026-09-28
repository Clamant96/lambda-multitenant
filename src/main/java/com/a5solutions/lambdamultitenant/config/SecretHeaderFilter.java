package com.a5solutions.lambdamultitenant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Porta de entrada do multitenant: le o header X-Secret-Id e define o tenant da requisicao.
 */
@Component
public class SecretHeaderFilter extends OncePerRequestFilter {

    public static final String SECRET_HEADER = "X-Secret-Id";

    private static final Logger log = LoggerFactory.getLogger(SecretHeaderFilter.class);

    private final DatabaseConfig.TenantRoutingDataSource routingDataSource;

    public SecretHeaderFilter(DatabaseConfig.TenantRoutingDataSource routingDataSource) {
        this.routingDataSource = routingDataSource;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String secretId = request.getHeader(SECRET_HEADER);

        // Fail-closed: sem tenant nao existe banco "padrao" para cair em silencio.
        if (secretId == null || secretId.isBlank()) {
            responderErro(response, "Header " + SECRET_HEADER + " e obrigatorio");
            return;
        }

        // Resolve o tenant antes de entrar no controller, para devolver 400 claro
        // em vez de um erro de conexao generico no meio da transacao JPA.
        try {
            boolean criado = routingDataSource.registrarTenant(secretId);
            log.info("[tenant={}] {}", secretId, criado
                    ? "1o acesso neste container: secret lido e pool criado"
                    : "pool reaproveitado do cache (sem ir ao Secrets Manager)");
        } catch (TenantInvalidoException e) {
            log.warn("[tenant={}] rejeitado: {}", secretId, e.getMessage());
            responderErro(response, e.getMessage());
            return;
        }

        try {
            SecretContextHolder.setSecretId(secretId);
            chain.doFilter(request, response);
        } finally {
            SecretContextHolder.clear();
        }
    }

    private void responderErro(HttpServletResponse response, String mensagem) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"erro\":\"" + mensagem.replace("\"", "'") + "\"}");
    }
}
