package com.shank.seat_management_service.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class SeatConfiguration implements WebMvcConfigurer {
    private final String token;
    public SeatConfiguration(@Value("${services.internal-token}") String token) {
        if (token.isBlank()) { throw new IllegalArgumentException("Internal service token must not be blank"); }
        this.token = token;
    }
    @Bean
    public Clock clock() { return Clock.systemUTC(); }
    @Bean
    public RestTemplate restTemplate(@Value("${services.connect-timeout-ms:3000}") int connect,
                                     @Value("${services.read-timeout-ms:5000}") int read) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return new RestTemplate(factory);
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                if (!"POST".equals(request.getMethod())) { return true; }
                String supplied = request.getHeader("X-Internal-Token");
                if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Internal service authentication required");
                }
                return true;
            }
        }).addPathPatterns("/api/seats/**");
    }
}