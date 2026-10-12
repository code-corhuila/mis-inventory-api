package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RolePermissionsTest {
    @Configuration @EnableMethodSecurity @Import({EntryApi.class,ExitApi.class,InventoryReadApi.class})
    static class Config {
        @Bean Entries entries() { return mock(Entries.class); }
        @Bean Exits exits() { return mock(Exits.class); }
        @Bean InventoryRead read() { return mock(InventoryRead.class); }
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
    }
    @Test void methodSecurityEnforcesRoleMatrix() {
        UUID actor=UUID.randomUUID(),id=UUID.randomUUID();
        var jwt=Jwt.withTokenValue("synthetic").header("alg","RS256").subject(actor.toString()).build();
        var request=new MockHttpServletRequest(); request.setAttribute("traceId","role-test");
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            for(String role:List.of("ADMIN","INVENTORY","SUPERVISOR","CONSULTATION")) {
                SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(actor.toString(),"unused","ROLE_"+role));
                var entry=context.getBean(EntryApi.class); var exit=context.getBean(ExitApi.class); var read=context.getBean(InventoryReadApi.class);
                assertDoesNotThrow(() -> read.stock(new LinkedMultiValueMap<>()));
                if(role.equals("CONSULTATION")) {
                    assertThrows(AccessDeniedException.class,() -> entry.find(id.toString()));
                    assertThrows(AccessDeniedException.class,() -> exit.find(id.toString()));
                    assertThrows(AccessDeniedException.class,() -> read.movements(new LinkedMultiValueMap<>()));
                } else {
                    assertDoesNotThrow(() -> entry.find(id.toString())); assertDoesNotThrow(() -> exit.find(id.toString()));
                    assertDoesNotThrow(() -> read.movements(new LinkedMultiValueMap<>()));
                }
                if(Set.of("ADMIN","INVENTORY").contains(role))
                    assertDoesNotThrow(() -> entry.transition(jwt,id.toString(),"activate","role-key",null,request));
                else assertThrows(AccessDeniedException.class,() -> entry.transition(jwt,id.toString(),"activate","role-key",null,request));
            }
        } finally { SecurityContextHolder.clearContext(); }
    }
}
