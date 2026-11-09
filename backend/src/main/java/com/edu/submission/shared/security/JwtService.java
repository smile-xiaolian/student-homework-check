package com.edu.submission.shared.security;

import com.edu.submission.shared.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret must contain at least 32 bytes");
        }
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(UserPrincipal principal) {
        return createToken(principal, TYPE_ACCESS, Duration.ofMinutes(properties.accessTokenMinutes()));
    }

    public String createRefreshToken(UserPrincipal principal) {
        return createToken(principal, TYPE_REFRESH, Duration.ofDays(properties.refreshTokenDays()));
    }

    public Optional<ParsedToken> parse(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String type = claims.get("type", String.class);
            if (!expectedType.equals(type)) {
                return Optional.empty();
            }
            Number version = claims.get("ver", Number.class);
            return Optional.of(new ParsedToken(
                    UUID.fromString(claims.getSubject()),
                    claims.get("role", String.class),
                    claims.get("name", String.class),
                    claims.get("userNo", String.class),
                    version == null ? 0 : version.intValue(),
                    claims.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public long accessTokenSeconds() {
        return Duration.ofMinutes(properties.accessTokenMinutes()).toSeconds();
    }

    public Duration refreshTokenDuration() {
        return Duration.ofDays(properties.refreshTokenDays());
    }

    public boolean refreshCookieSecure() {
        return properties.refreshCookieSecure();
    }

    private String createToken(UserPrincipal principal, String type, Duration timeToLive) {
        Instant issuedAt = Instant.now();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(principal.id().toString())
                .claim("type", type)
                .claim("role", principal.role().name())
                .claim("ver", principal.tokenVersion())
                .claim("name", principal.displayName())
                .claim("userNo", principal.userNo())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(timeToLive)))
                .signWith(signingKey)
                .compact();
    }

    public record ParsedToken(UUID userId, String role, String displayName, String userNo,
                              int tokenVersion, Instant expiresAt) {
    }
}

