package com.a5solutions.lambdamultitenant.config;

/**
 * Guarda o secretId (tenant) da requisicao corrente.
 * ThreadLocal: cada thread de requisicao enxerga apenas o seu proprio tenant.
 */
public final class SecretContextHolder {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private SecretContextHolder() {
    }

    public static void setSecretId(String secretId) {
        CONTEXT.set(secretId);
    }

    public static String getSecretId() {
        return CONTEXT.get();
    }

    // Obrigatorio no finally: o container Lambda reaproveita a thread entre invocacoes,
    // e sem limpar a proxima requisicao herdaria o tenant da anterior.
    public static void clear() {
        CONTEXT.remove();
    }
}
