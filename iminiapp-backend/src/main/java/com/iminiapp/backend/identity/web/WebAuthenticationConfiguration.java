package com.iminiapp.backend.identity.web;

import com.iminiapp.backend.shared.ratelimit.RedisRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebAuthenticationConfiguration implements WebMvcConfigurer {

    private final AuthenticationInterceptor authenticationInterceptor;
    private final AdminPermissionInterceptor adminPermissionInterceptor;
    private final RedisRateLimitInterceptor rateLimitInterceptor;

    public WebAuthenticationConfiguration(
            AuthenticationInterceptor authenticationInterceptor,
            AdminPermissionInterceptor adminPermissionInterceptor,
            RedisRateLimitInterceptor rateLimitInterceptor) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.adminPermissionInterceptor = adminPermissionInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/v1/app/**", "/api/v1/dining/**", "/api/members/**", "/api/v1/admin/**")
                .excludePathPatterns(
                        "/api/v1/app/system/health",
                        "/api/v1/app/auth/wechat-login",
                        "/api/v1/admin/auth/login",
                        "/api/v1/app/content/**",
                        "/api/v1/app/cultural-store/categories",
                        "/api/v1/app/cultural-store/products",
                        "/api/v1/app/cultural-store/products/*");
        registry.addInterceptor(adminPermissionInterceptor)
                .addPathPatterns("/api/v1/admin/**");
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/**");
    }
}
