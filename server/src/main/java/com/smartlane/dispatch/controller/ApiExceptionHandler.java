package com.smartlane.dispatch.controller;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
	private static final MediaType TEXT_PLAIN_UTF8 = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<String> handleResponseStatus(ResponseStatusException exception) {
		String reason = exception.getReason() == null ? "请求失败" : exception.getReason();
		return ResponseEntity.status(exception.getStatusCode())
				.contentType(TEXT_PLAIN_UTF8)
				.body(reason);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<String> handleValidation(MethodArgumentNotValidException exception) {
		String message = exception.getBindingResult().getFieldErrors().stream()
				.map(FieldError::getDefaultMessage)
				.collect(Collectors.joining(", "));
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.contentType(TEXT_PLAIN_UTF8)
				.body(message);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<String> handleUnreadableRequestBody(HttpMessageNotReadableException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.contentType(TEXT_PLAIN_UTF8)
				.body("请求体格式错误或包含未定义字段");
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<String> handleMethodValidation(HandlerMethodValidationException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.contentType(TEXT_PLAIN_UTF8)
				.body("请求参数校验失败");
	}
}
