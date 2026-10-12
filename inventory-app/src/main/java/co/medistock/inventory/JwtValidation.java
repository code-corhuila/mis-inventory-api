package co.medistock.inventory;

import java.time.Duration;
import java.util.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

public final class JwtValidation {
    private JwtValidation() { }

    public static OAuth2TokenValidator<Jwt> validator() {
        OAuth2TokenValidator<Jwt> required = jwt -> {
            try {
                String subject = jwt.getSubject();
                var roles = jwt.getClaimAsStringList("roles");
                Object kid = jwt.getHeaders().get("kid");
                boolean valid = subject != null && UUID.fromString(subject).toString().equals(subject)
                    && jwt.getExpiresAt() != null && jwt.getAudience().contains("mis-api")
                    && kid instanceof String key && !key.isBlank()
                    && roles != null && !roles.isEmpty()
                    && Set.of("ADMIN", "INVENTORY", "SUPERVISOR", "CONSULTATION").containsAll(roles);
                if (valid) return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException ignored) { }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid claims.", null));
        };
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)),
            new JwtIssuerValidator("mis-security"), required);
    }
}
