package com.smartlane.dispatch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.smartlane.dispatch.security.AuthenticatedUser;
import com.smartlane.dispatch.security.JwtService;

class WebSocketAuthorizationInterceptorTests {

	private JwtService jwtService;
	private WebSocketAuthorizationInterceptor interceptor;
	private MessageChannel channel;
	private AuthenticatedUser user;

	@BeforeEach
	void setUp() {
		jwtService = mock(JwtService.class);
		interceptor = new WebSocketAuthorizationInterceptor(jwtService);
		channel = mock(MessageChannel.class);
		user = new AuthenticatedUser("admin", "管理员", "ADMIN", "总控中心");
	}

	@Test
	void connectRequiresValidBearerToken() {
		Message<byte[]> missingToken = message(StompCommand.CONNECT, accessor -> { });
		assertThatThrownBy(() -> interceptor.preSend(missingToken, channel))
				.isInstanceOf(AccessDeniedException.class);

		when(jwtService.parse("valid-token")).thenReturn(Optional.of(user));
		StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
		accessor.setNativeHeader("Authorization", "Bearer valid-token");
		Message<byte[]> validConnect = build(accessor);

		interceptor.preSend(validConnect, channel);

		assertThat(accessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
		assertThat(((UsernamePasswordAuthenticationToken) accessor.getUser()).getPrincipal()).isEqualTo(user);
	}

	@Test
	void onlyAuthenticatedOperationsSubscriptionIsAllowed() {
		Message<byte[]> allowed = authenticatedMessage(
				StompCommand.SUBSCRIBE,
				accessor -> accessor.setDestination(WebSocketAuthorizationInterceptor.OPERATIONS_TOPIC));
		assertThat(interceptor.preSend(allowed, channel)).isSameAs(allowed);

		Message<byte[]> otherTopic = authenticatedMessage(
				StompCommand.SUBSCRIBE,
				accessor -> accessor.setDestination("/topic/internal"));
		assertThatThrownBy(() -> interceptor.preSend(otherTopic, channel))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void clientsCannotPublishToBrokerTopics() {
		Message<byte[]> send = authenticatedMessage(
				StompCommand.SEND,
				accessor -> accessor.setDestination(WebSocketAuthorizationInterceptor.OPERATIONS_TOPIC));

		assertThatThrownBy(() -> interceptor.preSend(send, channel))
				.isInstanceOf(AccessDeniedException.class);
	}

	private Message<byte[]> authenticatedMessage(
			StompCommand command,
			Consumer<StompHeaderAccessor> customizer) {
		return message(command, accessor -> {
			accessor.setUser(new UsernamePasswordAuthenticationToken(
					user,
					"token",
					List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
			customizer.accept(accessor);
		});
	}

	private Message<byte[]> message(StompCommand command, Consumer<StompHeaderAccessor> customizer) {
		StompHeaderAccessor accessor = accessor(command);
		customizer.accept(accessor);
		return build(accessor);
	}

	private StompHeaderAccessor accessor(StompCommand command) {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
		accessor.setLeaveMutable(true);
		return accessor;
	}

	private Message<byte[]> build(StompHeaderAccessor accessor) {
		return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
	}
}
