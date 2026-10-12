package co.medistock.inventory;

import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class JwtValidationTest {
    private static KeyPair pair;
    private JwtDecoder decoder;
    private JwtEncoder encoder;

    @BeforeAll static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair = generator.generateKeyPair();
    }

    @BeforeEach void setup() {
        var rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
            .keyID("test-key").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
        var publicDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic()).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        publicDecoder.setJwtValidator(JwtValidation.validator()); decoder = publicDecoder;
    }

    private String token(String fault, String role) {
        var claims = JwtClaimsSet.builder().issuer("mis-security").subject(UUID.randomUUID().toString())
            .audience(List.of("mis-api")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
            .claim("roles", List.of(role));
        switch (fault) {
            case "issuer" -> claims.issuer("wrong");
            case "audience" -> claims.audience(List.of("wrong"));
            case "subject" -> claims.subject("invalid");
            case "expired" -> claims.issuedAt(Instant.now().minusSeconds(300)).expiresAt(Instant.now().minusSeconds(120));
            case "expiry" -> claims.claims(values -> values.remove("exp"));
            case "roles" -> claims.claim("roles", List.of());
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims.build())).getTokenValue();
    }

    @Test void acceptsFourRoles() {
        for (String role : List.of("ADMIN", "INVENTORY", "SUPERVISOR", "CONSULTATION")) {
            assertDoesNotThrow(() -> decoder.decode(token("", role)));
        }
    }

    @Test void rejectsInvalidClaims() {
        for (String fault : List.of("issuer", "audience", "subject", "expired", "expiry", "roles")) {
            assertThrows(JwtException.class, () -> decoder.decode(token(fault, "ADMIN")));
        }
        assertThrows(JwtException.class, () -> decoder.decode(token("", "UNKNOWN")));
    }

    @Test void rejectsForgedSignature() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var other = NimbusJwtDecoder.withPublicKey((RSAPublicKey) generator.generateKeyPair().getPublic()).build();
        assertThrows(JwtException.class, () -> other.decode(token("", "ADMIN")));
    }

    @Test void rejectsUnsignedTokens() {
        assertThrows(JwtException.class, () -> decoder.decode("eyJhbGciOiJub25lIn0.eyJzdWIiOiJmb3JnZWQifQ."));
    }

    @Test void rejectsHmacEvenWithValidClaims() throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("mis-security").audience("mis-api")
            .subject(UUID.randomUUID().toString()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("roles", List.of("ADMIN")).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("test-key").build(), claims);
        token.sign(new MACSigner(new byte[32]));
        assertThrows(JwtException.class, () -> decoder.decode(token.serialize()));
    }
}
