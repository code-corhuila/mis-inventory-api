package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RolePermissionsTest {
    @Configuration @EnableMethodSecurity @Import({EntryApi.class,ExitApi.class,InventoryReadApi.class,StockEventApi.class,ExpiryAlertApi.class})
    static class Config {
        @Bean Entries entries() { return mock(Entries.class); }
        @Bean Exits exits() { return mock(Exits.class); }
        @Bean InventoryRead read() { return mock(InventoryRead.class); }
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean StockEvents stockEvents() { return mock(StockEvents.class); }
        @Bean ExpiryAlerts expiryAlerts() { return mock(ExpiryAlerts.class); }
        @Bean ExpiryAlertRead expiryRead() { return mock(ExpiryAlertRead.class); }
    }
    @Test void methodSecurityEnforcesRoleMatrix() {
        UUID actor=UUID.randomUUID(),id=UUID.randomUUID();
        var jwt=Jwt.withTokenValue("synthetic").header("alg","RS256").subject(actor.toString()).build();
        var request=new MockHttpServletRequest(); request.setAttribute("traceId","role-test");
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            for(String role:List.of("ADMIN","INVENTORY","SUPERVISOR","CONSULTATION")) {
                SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                    jwt,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role))));
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
    @Test void expiryPermissionsSeparateHumansFromWorker() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var endpoint=context.getBean(ExpiryAlertApi.class);
            var body=Map.<String,Object>of("lotId",UUID.randomUUID().toString(),"windowDays",30);
            when(context.getBean(ExpiryAlerts.class).evaluate(any(),any(),eq(30))).thenReturn(Optional.empty());
            for(String service:List.of("human","mis-worker","mis-workflow","mis-inventory-relay"))
                for(String role:List.of("ADMIN","INVENTORY","SUPERVISOR","CONSULTATION")) {
                    var builder=Jwt.withTokenValue("synthetic").header("alg","RS256").subject(UUID.randomUUID().toString());
                    if(!service.equals("human")) builder.claim("service",service);
                    var jwt=builder.build();
                    SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        jwt,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role))));
                    if(service.equals("human") && !role.equals("CONSULTATION")) assertDoesNotThrow(() -> endpoint.list(new LinkedMultiValueMap<>()));
                    else assertThrows(AccessDeniedException.class,() -> endpoint.list(new LinkedMultiValueMap<>()));
                    if(service.equals("mis-worker") && role.equals("INVENTORY")) assertDoesNotThrow(() -> endpoint.evaluate(jwt,body));
                    else assertThrows(AccessDeniedException.class,() -> endpoint.evaluate(jwt,body));
                }
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void eventEndpointRequiresSignedWorkerServiceClaimAndInventoryRole() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            for(String service:List.of("human","mis-workflow","mis-inventory-relay","mis-worker")) {
                var builder=Jwt.withTokenValue("synthetic").header("alg","RS256").subject(UUID.randomUUID().toString());
                if(!service.equals("human")) builder.claim("service",service);
                var jwt=builder.build();
                SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                    jwt,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_INVENTORY"))));
                var endpoint=context.getBean(StockEventApi.class);
                if(service.equals("mis-worker")) {
                    assertThrows(InventoryFailure.class,() -> endpoint.apply(jwt,Map.of()));
                    SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        jwt,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
                    assertThrows(AccessDeniedException.class,() -> endpoint.apply(jwt,Map.of()));
                }
                else assertThrows(AccessDeniedException.class,() -> endpoint.apply(jwt,Map.of()));
                var request=new MockHttpServletRequest(); request.setAttribute("traceId","role-test");
                var entries=context.getBean(EntryApi.class); String id=UUID.randomUUID().toString();
                if(Set.of("human","mis-workflow").contains(service))
                    assertDoesNotThrow(() -> entries.transition(jwt,id,"activate","role-key",null,request));
                else assertThrows(AccessDeniedException.class,() -> entries.transition(jwt,id,"activate","role-key",null,request));
                if(!service.equals("human")) assertThrows(AccessDeniedException.class,() -> context.getBean(ExitApi.class).create(jwt,"role-key",null,request));
            }
        } finally { SecurityContextHolder.clearContext(); }
    }
}
