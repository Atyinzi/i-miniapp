package com.iminiapp.backend.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Defense in depth for legacy or future test endpoints accidentally present on the classpath. */
@Configuration
@Profile("prod")
public class ProductionEndpointGuardConfiguration implements WebMvcConfigurer {
    private final ProductionEndpointGuard interceptor = new ProductionEndpointGuard();

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/**").order(Ordered.HIGHEST_PRECEDENCE);
    }

    static final class ProductionEndpointGuard implements HandlerInterceptor {
        private static final Pattern UNSAFE_SEGMENT =
                Pattern.compile("(^|/)(mock|test)([-_/]|$)", Pattern.CASE_INSENSITIVE);

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws IOException {
            if (!isUnsafePath(request.getRequestURI())) return true;
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        static boolean isUnsafePath(String requestUri) {
            if (requestUri == null) return false;
            String path = requestUri.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
            return UNSAFE_SEGMENT.matcher(path).find();
        }
    }
}
