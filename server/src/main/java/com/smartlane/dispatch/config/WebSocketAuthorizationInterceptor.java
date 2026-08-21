package com.smartlane.dispatch.config;

import java.util.List;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import com.smartlane.dispatch.security.AuthenticatedUser;
import com.smartlane.dispatch.security.JwtService;

/** Authenticates STOMP sessions and keeps the broker topic server-publish-only. */
@Component
public class WebSocketAuthorizationInterceptor implements ChannelInterceptor {

	static final String OPERATIONS_TOPIC = "/topic/operations";

	private final JwtService jwtService;

	public WebSocketAuthorizationInterceptor(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null || accessor.getCommand() == null) {
			return message;
		}

		StompCommand command = accessor.getCommand();
		if (StompCommand.CONNECT.equals(command)) {
			authenticate(accessor);
			return message;
		}
		if (StompCommand.SEND.equals(command) || StompCommand.MESSAGE.equals(command)) {
			throw new AccessDeniedException("WebSocket topics are server-publish-only");
		}
		if (StompCommand.SUBSCRIBE.equals(command)) {
			requireAuthenticated(accessor);
			if (!OPERATIONS_TOPIC.equals(accessor.getDestination())) {
				throw new AccessDeniedException("WebSocket destination is not allowed");
			}
		}
		return message;
	}

	private void authenticate(StompHeaderAccessor accessor) {
		String authorization = firstNonBlank(
				accessor.getFirstNativeHeader("Authorization"),
				accessor.getFirstNativeHeader("authorization"));
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			throw new AccessDeniedException("WebSocket authentication required");
		}
		String token = authorization.substring(7).trim();
		AuthenticatedUser user = jwtService.parse(token)
				.orElseThrow(() -> new AccessDeniedException("Invalid WebSocket token"));
		Authentication authentication = new UsernamePasswordAuthenticationToken(
				user,
				token,
				List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
		accessor.setUser(authentication);
	}

	private void requireAuthenticated(StompHeaderAccessor accessor) {
		if (!(accessor.getUser() instanceof Authentication authentication) || !authentication.isAuthenticated()) {
			throw new AccessDeniedException("WebSocket authentication required");
		}
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}
}
