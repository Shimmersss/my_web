package com.web.backen.admin;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.runtime.TaskCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OperationsController.class)
class OperationsHttpTest {
    @Autowired MockMvc mvc;
    @MockBean AuthService auth;
    @MockBean TaskCoordinator coordinator;
    @Test void readRequiresRoot() throws Exception {
        when(auth.requireRoot(any())).thenThrow(new AuthException(403, "root required"));
        mvc.perform(get("/api/admin/operations")).andExpect(status().isForbidden());
        verifyNoInteractions(coordinator);
    }
    @Test void writesRequireCsrfAndValidateAction() throws Exception {
        doThrow(new AuthException(403, "csrf required")).when(auth).requireCsrf(any());
        mvc.perform(put("/api/admin/operations/admission").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"pause\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(coordinator);
        doNothing().when(auth).requireCsrf(any());
        mvc.perform(put("/api/admin/operations/admission").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"other\"}")).andExpect(status().isBadRequest());
        when(coordinator.snapshot()).thenReturn(Map.of("accepting", false));
        mvc.perform(put("/api/admin/operations/admission").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"pause\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.accepting").value(false));
        verify(coordinator).pause();
    }
}
