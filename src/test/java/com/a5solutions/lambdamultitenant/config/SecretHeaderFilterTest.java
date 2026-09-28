package com.a5solutions.lambdamultitenant.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecretHeaderFilterTest {

    private final DatabaseConfig.TenantRoutingDataSource routing = mock(DatabaseConfig.TenantRoutingDataSource.class);
    private final SecretHeaderFilter filter = new SecretHeaderFilter(routing);

    @AfterEach
    void limpar() {
        SecretContextHolder.clear();
    }

    @Test
    void semHeader_retorna400_eNaoChamaController() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/usuario"), response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("X-Secret-Id");
        assertThat(chain.getRequest()).isNull();
        verify(routing, never()).registrarTenant(anyString());
    }

    @Test
    void headerEmBranco_retorna400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/usuario");
        request.addHeader(SecretHeaderFilter.SECRET_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void secretInexistente_retorna400_comMensagemDoTenant() throws Exception {
        when(routing.registrarTenant("workshop/nao-existe"))
                .thenThrow(new TenantInvalidoException("Secret 'workshop/nao-existe' nao encontrado", null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/usuario");
        request.addHeader(SecretHeaderFilter.SECRET_HEADER, "workshop/nao-existe");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("workshop/nao-existe");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void headerValido_defineTenantDuranteARequisicao_eLimpaNoFinal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/usuario");
        request.addHeader(SecretHeaderFilter.SECRET_HEADER, "workshop/tenant-a");
        AtomicReference<String> tenantDentroDoController = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> tenantDentroDoController.set(SecretContextHolder.getSecretId()));

        assertThat(tenantDentroDoController.get()).isEqualTo("workshop/tenant-a");
        assertThat(SecretContextHolder.getSecretId()).isNull();
        verify(routing).registrarTenant("workshop/tenant-a");
    }
}
