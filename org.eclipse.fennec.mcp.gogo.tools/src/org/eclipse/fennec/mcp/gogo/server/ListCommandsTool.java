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
package org.eclipse.fennec.mcp.gogo.server;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.apache.felix.service.command.CommandProcessor;
import org.eclipse.fennec.mcp.api.AbstractMCPTool;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP Tool that lists all available Gogo shell commands in the running OSGi framework.
 */
@Component(name = "ListCommandsTool", service = MCPTool.class, property = "tool.name=list_commands")
public class ListCommandsTool extends AbstractMCPTool {

	/** Upper bound on captured output, guarding against OOM from unbounded help output. */
	static final int MAX_OUTPUT_BYTES = 1_048_576;

	@Reference
	private CommandProcessor commandProcessor;

	/** Interruptible worker pool so a cancelled (timed-out) command is actually torn down; see {@link GogoCommandRunner}. */
	private ExecutorService executor;

	@Activate
	void activate() {
		this.executor = GogoCommandRunner.newBoundedExecutor("list-commands-worker");
		this.name = "list_commands";
		this.description = "List all available Gogo shell commands in the running OSGi framework. " +
				"Optionally filter by scope (e.g. 'scr', 'felix', 'obr'). " +
				"Use this to discover what commands are available before executing them.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"scope": {
							"type": "string",
							"description": "Optional scope filter to show only commands from a specific scope, e.g. 'scr', 'felix', 'gogo'"
						}
					}
				}
				""";
	}

	@Deactivate
	void deactivate() {
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	/**
	 * Executes the Gogo {@code help} command and optionally filters the output
	 * by scope. If a scope is given, only lines containing the scope string
	 * (case-insensitive) are returned; if no matches, the full list is included as fallback.
	 * The command runs interruptibly, so a request timeout tears it down (see {@link GogoCommandRunner}).
	 */
	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		Object rawScope = arguments == null ? null : arguments.get("scope");
		String scope = rawScope instanceof String s ? s : null;

		return GogoCommandRunner.run(commandProcessor, executor, "help", MAX_OUTPUT_BYTES)
				.map(o -> {
					String output = o.stdout();

					if (scope != null && !scope.isBlank()) {
						// Filter lines containing the scope
						String scopeLower = scope.toLowerCase();
						StringBuilder filtered = new StringBuilder();
						filtered.append("**Available commands in scope '").append(scope).append("':**\n\n");
						boolean found = false;
						for (String line : output.split("\n")) {
							if (line.toLowerCase().contains(scopeLower)) {
								filtered.append(line).append("\n");
								found = true;
							}
						}
						if (!found) {
							filtered.append("No commands found for scope '").append(scope).append("'.\n");
							filtered.append("\nAll available commands:\n").append(output);
						}
						return McpSchema.CallToolResult.builder()
								.addTextContent(filtered.toString())
								.build();
					}

					return McpSchema.CallToolResult.builder()
							.addTextContent("**Available Gogo commands:**\n\n" + output)
							.build();
				})
				.onErrorResume(e -> Mono.just(
						McpSchema.CallToolResult.builder()
								.addTextContent("Error listing commands: " + e.getMessage())
								.isError(true)
								.build()));
	}
}
