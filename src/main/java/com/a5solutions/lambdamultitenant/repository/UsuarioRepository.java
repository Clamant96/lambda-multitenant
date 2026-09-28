package com.a5solutions.lambdamultitenant.repository;

import com.a5solutions.lambdamultitenant.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
}
