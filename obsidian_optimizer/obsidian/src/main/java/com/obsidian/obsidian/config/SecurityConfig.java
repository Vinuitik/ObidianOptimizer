package com.obsidian.obsidian.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.auth.username}")
    private String username;

    @Value("${app.auth.password}")
    private String password;

    @Value("${app.auth.remember-key}")
    private String rememberKey;

    @Value("${app.auth.remember-validity-seconds}")
    private int rememberValiditySeconds;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/logout").permitAll()
                // service-to-service agent API — token-guarded inside the
                // controller (X-Internal-Token = MCP_API_TOKEN, fail closed)
                .requestMatchers("/api/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginProcessingUrl("/login")
                .successHandler((req, res, authentication) ->
                    res.setStatus(HttpServletResponse.SC_OK))
                .failureHandler((req, res, ex) ->
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
                .permitAll()
            )
            // Long-lived remember-me: sign-in issues a persistent, signed token cookie so the
            // user stays authenticated across app-close AND backend restarts/redeploys (the
            // hash-based token is stateless — it re-validates against the user, so there's no
            // in-memory session to lose). alwaysRemember → every login gets the cookie without
            // a form checkbox. The fixed `key` is what makes tokens survive restarts.
            .rememberMe(rm -> rm
                .key(rememberKey)
                .tokenValiditySeconds(rememberValiditySeconds)
                .alwaysRemember(true)
                .userDetailsService(userDetailsService())
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler((req, res, auth) ->
                    res.setStatus(HttpServletResponse.SC_OK))
                .deleteCookies("remember-me")
                .permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) ->
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
            );
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.builder()
            .username(username)
            .password(passwordEncoder().encode(password))
            .roles("USER")
            .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
