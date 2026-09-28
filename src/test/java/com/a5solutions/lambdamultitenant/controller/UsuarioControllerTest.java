package com.a5solutions.lambdamultitenant.controller;

import com.a5solutions.lambdamultitenant.model.Usuario;
import com.a5solutions.lambdamultitenant.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UsuarioControllerTest {

    private UsuarioRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(UsuarioRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UsuarioController(repository)).build();
    }

    @Test
    void listar_naoExpoeSenha() throws Exception {
        when(repository.findAll()).thenReturn(List.of(new Usuario("ana", "ana@a.com", "hash")));

        mockMvc.perform(get("/usuario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].usuario").value("ana"))
                .andExpect(jsonPath("$[0].senha").doesNotExist());
    }

    @Test
    void buscar_inexistente_retorna404() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/usuario/99")).andExpect(status().isNotFound());
    }

    @Test
    void cadastrar_gravaSenhaComHash() throws Exception {
        when(repository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/usuario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuario\":\"novo\",\"email\":\"novo@a.com\",\"senha\":\"123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("novo@a.com"))
                .andExpect(jsonPath("$.senha").doesNotExist());

        ArgumentCaptor<Usuario> captor = ArgumentCaptor.forClass(Usuario.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSenha()).startsWith("$2a$").isNotEqualTo("123456");
    }

    @Test
    void cadastrar_semCampoObrigatorio_retorna400() throws Exception {
        mockMvc.perform(post("/usuario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuario\":\"novo\",\"email\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(repository, never()).save(any());
    }
}
