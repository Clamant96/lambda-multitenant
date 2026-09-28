package com.a5solutions.lambdamultitenant.dto;

public record UsuarioRequest(String usuario, String email, String senha) {

    public boolean valido() {
        return naoVazio(usuario) && naoVazio(email) && naoVazio(senha);
    }

    private static boolean naoVazio(String valor) {
        return valor != null && !valor.isBlank();
    }
}
