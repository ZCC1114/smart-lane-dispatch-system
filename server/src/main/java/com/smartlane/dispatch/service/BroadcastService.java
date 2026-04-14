package com.smartlane.dispatch.service;

import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class BroadcastService {

	private final SimpMessagingTemplate messagingTemplate;

	public BroadcastService(SimpMessagingTemplate messagingTemplate) {
		this.messagingTemplate = messagingTemplate;
	}

	public void operationsChanged(String action) {
		messagingTemplate.convertAndSend("/topic/operations", Map.of("action", action));
	}
}
