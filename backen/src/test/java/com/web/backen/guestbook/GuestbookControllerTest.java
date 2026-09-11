package com.web.backen.guestbook;

import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import com.web.backen.settings.RuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class GuestbookControllerTest {
    private final AuthUser user = new AuthUser(7L, "member", "USER", 0, true);
    private final AuthUser root = new AuthUser(1L, "root", "ROOT", 0, true);

    @Test
    void publicReadsWorkWithoutSessionButUserAndRootVisibilityAreEnforced() {
        GuestbookService service = mock(GuestbookService.class);
        AuthService auth = mock(AuthService.class);
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        GuestbookController controller = new GuestbookController(service, auth, runtime);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(service.messages(1, null)).thenReturn(Map.of("items", java.util.List.of()));
        when(auth.currentUser(request)).thenReturn(Optional.empty());
        when(runtime.visibilityLevel("Guestbook")).thenReturn("PUBLIC");
        assertEquals(200, controller.messages(1, request).getStatusCode().value());

        when(runtime.visibilityLevel("Guestbook")).thenReturn("USER");
        doThrow(new AuthException(401, "请先登录")).when(auth).requireUser(request);
        assertEquals(401, controller.messages(1, request).getStatusCode().value());

        when(runtime.visibilityLevel("Guestbook")).thenReturn("ROOT");
        doThrow(new AuthException(403, "需要 root 权限")).when(auth).requireRoot(request);
        assertEquals(403, controller.messages(1, request).getStatusCode().value());
    }

    @Test
    void mutationsRequireCsrfAndAuthenticatedUser() {
        GuestbookService service = mock(GuestbookService.class);
        AuthService auth = mock(AuthService.class);
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        GuestbookController controller = new GuestbookController(service, auth, runtime);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(runtime.visibilityLevel("Guestbook")).thenReturn("PUBLIC");
        doThrow(new AuthException(403, "缺少 CSRF token")).when(auth).requireCsrf(request);
        assertEquals(403, controller.create(Map.of("content", "hello"), request).getStatusCode().value());
        verify(service, never()).createMessage(any(), any());

        reset(auth);
        when(runtime.visibilityLevel("Guestbook")).thenReturn("PUBLIC");
        when(auth.requireUser(request)).thenReturn(user);
        when(service.createMessage(user, "hello")).thenReturn(Map.of("id", 1L));
        ResponseEntity<?> response = controller.create(Map.of("content", "hello"), request);
        assertEquals(200, response.getStatusCode().value());
        verify(auth).requireCsrf(request);
        verify(service).createMessage(user, "hello");
    }

    @Test
    void rootAdminDeleteRequiresCsrfAndRootSession() {
        GuestbookService service = mock(GuestbookService.class);
        AuthService auth = mock(AuthService.class);
        AdminGuestbookController controller = new AdminGuestbookController(service, auth);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(auth.requireRoot(request)).thenReturn(root);
        assertEquals(200, controller.delete(9, request).getStatusCode().value());
        verify(auth).requireCsrf(request);
        verify(service).delete(9, root);
    }
}
