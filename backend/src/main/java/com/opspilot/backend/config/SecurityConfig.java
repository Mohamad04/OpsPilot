package com.opspilot.backend.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public ApiSecurityExceptionHandler apiSecurityExceptionHandler(JsonMapper jsonMapper) {
        return new ApiSecurityExceptionHandler(jsonMapper);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                  ApiSecurityExceptionHandler errorHandler) throws Exception {
        http
                .authorizeHttpRequests(
                        authorize -> authorize
                                .requestMatchers("/api/**")
                                .authenticated()
                                .anyRequest()
                                .permitAll()
                )
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(Customizer.withDefaults())
                                .authenticationEntryPoint(errorHandler)
                                .accessDeniedHandler(errorHandler)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        return http.build();
    }
}
