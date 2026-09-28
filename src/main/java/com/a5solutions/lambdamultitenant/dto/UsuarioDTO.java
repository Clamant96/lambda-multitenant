package com.a5solutions.lambdamultitenant.dto;

import com.a5solutions.lambdamultitenant.model.Usuario;

// Sem a senha: entidade nunca sai direto do controller.
public record UsuarioDTO(Long id, String usuario, String email) {

    public static UsuarioDTO from(Usuario usuario) {
        return new UsuarioDTO(usuario.getId(), usuario.getUsuario(), usuario.getEmail());
    }
}
