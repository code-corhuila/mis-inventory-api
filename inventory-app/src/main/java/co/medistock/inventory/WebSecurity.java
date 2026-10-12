package co.medistock.inventory;

import co.medistock.inventory.adapters.Errors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableMethodSecurity
public class WebSecurity {
    @Bean JwtDecoder decoder(@Value("${inventory.jwks-url}") String uri) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(2000);
        var decoder = NimbusJwtDecoder.withJwkSetUri(uri).jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(factory)).build();
        decoder.setJwtValidator(JwtValidation.validator());
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder, Errors errors) throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles"); roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter(); converter.setJwtGrantedAuthoritiesConverter(roles);
        var resolver = new DefaultBearerTokenResolver();
        http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/health").permitAll().anyRequest().authenticated())
            .oauth2ResourceServer(r -> r.bearerTokenResolver(req -> "/health".equals(req.getRequestURI()) ? null : resolver.resolve(req))
                .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((req,res,e) -> errors.write(req,res,401,"UNAUTHORIZED","Autenticacion requerida.")))
            .exceptionHandling(h -> h.accessDeniedHandler((req,res,e) -> errors.write(req,res,403,"FORBIDDEN","Permisos insuficientes.")));
        return http.build();
    }
}
