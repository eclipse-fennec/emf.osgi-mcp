/*
 * ******************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Data In Motion Consulting - initial implementation
 * ******************************************************************
 */
package org.eclipse.fennec.mcp.http.component.tests;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.fennec.mcp.api.MCPTool;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import reactor.core.publisher.Mono;

/**
 * An {@link MCPTool} whose behaviour a test dictates. Registered as a whiteboard service
 * so it travels the real path - provider aggregation, schema validation, SDK dispatch -
 * instead of being handed to the server directly.
 */
class TestMCPTool implements MCPTool {

	private static final String ECHO_SCHEMA = """
			{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""";

	private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

	private final String name;
	private final String description;
	private final String inputSchema;
	private final Function<Map<String, Object>, Mono<CallToolResult>> behaviour;

	private TestMCPTool(String name, String description, String inputSchema,
			Function<Map<String, Object>, Mono<CallToolResult>> behaviour) {
		this.name = name;
		this.description = description;
		this.inputSchema = inputSchema;
		this.behaviour = behaviour;
	}

	/** A tool that echoes its {@code text} argument back as text content. */
	static TestMCPTool echo(String name) {
		return new TestMCPTool(name, "Echoes the text argument back", ECHO_SCHEMA,
				arguments -> Mono.just(CallToolResult.builder()
						.content(List.of(TextContent.builder("echo: " + arguments.get("text")).build()))
						.build()));
	}

	/** A tool that fails, to see how a failure reaches the client. */
	static TestMCPTool failing(String name) {
		return new TestMCPTool(name, "Always fails", EMPTY_SCHEMA,
				arguments -> Mono.error(new IllegalStateException("deliberate failure of " + name)));
	}

	/** A tool nobody calls - it exists so a provider can be satisfied. */
	static TestMCPTool inert(String name) {
		return new TestMCPTool(name, "Exists so the tool provider can be satisfied", EMPTY_SCHEMA,
				arguments -> Mono.error(new UnsupportedOperationException(name + " is never called")));
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public String getInputSchema() {
		return inputSchema;
	}

	@Override
	public String getOutputSchema() {
		return null;
	}

	@Override
	public Mono<CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return behaviour.apply(arguments);
	}
}
