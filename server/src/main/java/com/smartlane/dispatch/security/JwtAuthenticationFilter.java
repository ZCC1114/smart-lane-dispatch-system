package com.smartlane.dispatch.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtService jwtService;

	public JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String authorization = request.getHeader("Authorization");
		if (authorization != null && authorization.startsWith("Bearer ")) {
			String token = authorization.substring(7);
			jwtService.parse(token).ifPresent(user -> {
				UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
						user,
						token,
						List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			});
		}

		filterChain.doFilter(request, response);
	}
}
