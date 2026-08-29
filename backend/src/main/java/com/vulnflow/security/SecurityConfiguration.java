package com.vulnflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import com.vulnflow.ui.auth.PasswordChangeRequiredFilter;
import com.vulnflow.ui.auth.UiEnabledFilter;
import com.vulnflow.ui.UiProperties;

@Configuration
public class SecurityConfiguration {

    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(
            ApiKeyAuthenticationFilter filter) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean UiEnabledFilter uiEnabledFilter(UiProperties properties){return new UiEnabledFilter(properties);}
    @Bean FilterRegistrationBean<UiEnabledFilter> uiEnabledFilterRegistration(UiEnabledFilter filter){FilterRegistrationBean<UiEnabledFilter> registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;}

    @Bean
    @Order(1)
    SecurityFilterChain machineApiSecurityFilterChain(
            HttpSecurity http,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            ApiKeyAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        return http
                .securityMatcher("/api/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain uiSecurityFilterChain(HttpSecurity http, PasswordChangeRequiredFilter passwordFilter,UiEnabledFilter enabledFilter)
            throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("XSRF-TOKEN");
        csrf.setHeaderName("X-XSRF-TOKEN");
        csrf.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(true).path("/"));
        return http
                .securityMatcher("/api/ui/v1/**")
                .csrf(configurer -> configurer.csrfTokenRepository(csrf))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/ui/v1/auth/csrf", "/api/ui/v1/auth/login").permitAll()
                        .requestMatchers("/api/ui/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults())
                .addFilterBefore(enabledFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(passwordFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain publicSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
