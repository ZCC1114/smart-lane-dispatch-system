package com.smartlane.dispatch.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlane.dispatch.entity.Lane;
import com.smartlane.dispatch.service.OperationsService;

@RestController
@RequestMapping("/api/lanes")
public class LaneController {

	private final OperationsService operationsService;

	public LaneController(OperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping
	public List<Lane> getLanes() {
		return operationsService.getLanes();
	}
}
