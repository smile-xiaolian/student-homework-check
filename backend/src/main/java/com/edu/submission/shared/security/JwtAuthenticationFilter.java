package com.edu.submission.shared.security;

import com.edu.submission.identity.application.IdentityQueryService;
import com.edu.submission.identity.domain.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final IdentityQueryService identityQueryService;

    public JwtAuthenticationFilter(JwtService jwtService, IdentityQueryService identityQueryService) {
        this.jwtService = jwtService;
        this.identityQueryService = identityQueryService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            resolveBearerToken(request).flatMap(token -> jwtService.parse(token, JwtService.TYPE_ACCESS))
                    .flatMap(token -> identityQueryService.findById(token.userId())
                            .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                            .filter(user -> user.getTokenVersion() != null
                                    && user.getTokenVersion() == token.tokenVersion())
                            .map(UserPrincipal::from))
                    .ifPresent(principal -> {
                        var authority = new SimpleGrantedAuthority("ROLE_" + principal.role().name());
                        var authentication = new UsernamePasswordAuthenticationToken(
                                principal, null, List.of(authority));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }
        filterChain.doFilter(request, response);
    }

    private java.util.Optional<String> resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return java.util.Optional.empty();
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(token);
    }
}

