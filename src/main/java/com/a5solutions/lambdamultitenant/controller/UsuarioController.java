package com.a5solutions.lambdamultitenant.controller;

import com.a5solutions.lambdamultitenant.dto.UsuarioDTO;
import com.a5solutions.lambdamultitenant.dto.UsuarioRequest;
import com.a5solutions.lambdamultitenant.model.Usuario;
import com.a5solutions.lambdamultitenant.repository.UsuarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CRUD comum, sem nenhuma linha sobre tenant: o banco ja foi escolhido pelo SecretHeaderFilter.
 */
@RestController
@RequestMapping("/usuario")
public class UsuarioController {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UsuarioController(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    @GetMapping
    public List<UsuarioDTO> listar() {
        return usuarioRepository.findAll().stream().map(UsuarioDTO::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UsuarioDTO> buscar(@PathVariable Long id) {
        return usuarioRepository.findById(id)
                .map(UsuarioDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UsuarioDTO> cadastrar(@RequestBody UsuarioRequest request) {
        if (request == null || !request.valido()) {
            return ResponseEntity.badRequest().build();
        }
        Usuario salvo = usuarioRepository.save(
                new Usuario(request.usuario(), request.email(), passwordEncoder.encode(request.senha())));
        return ResponseEntity.status(HttpStatus.CREATED).body(UsuarioDTO.from(salvo));
    }
}
