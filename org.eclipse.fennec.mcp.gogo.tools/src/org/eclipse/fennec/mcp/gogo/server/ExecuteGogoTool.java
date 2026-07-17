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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.eclipse.fennec.mcp.api.AbstractMCPTool;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP Tool that executes arbitrary Gogo shell commands in the running OSGi framework.
 */
@Component(name = "ExecuteGogoTool", service = MCPTool.class, property = "tool.name=execute_gogo")
public class ExecuteGogoTool extends AbstractMCPTool {

	/** Upper bound on captured stdout/stderr per command, guarding against OOM from unbounded output. */
	static final int MAX_OUTPUT_BYTES = 1_048_576;

	@Reference
	private CommandProcessor commandProcessor;

	@Activate
	void activate() {
		this.name = "execute_gogo";
		this.description = "Execute a Gogo shell command in the running OSGi framework. " +
				"This gives you direct access to the OSGi runtime — list bundles (lb), " +
				"inspect DS components (scr:list, scr:info), check capabilities, " +
				"manage configurations, and more. Use 'help' to list all available commands.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"command": {
							"type": "string",
							"description": "The Gogo shell command to execute, e.g. 'lb', 'scr:list', 'help', 'inspect cap osgi.wiring.package 5'"
						}
					},
					"required": ["command"]
				}
				""";
	}

	/**
	 * Executes the given Gogo command by creating an isolated {@link CommandSession}
	 * with captured stdout/stderr streams. Returns the output formatted as markdown
	 * with the command echoed, stdout content, and any stderr output.
	 */
	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return Mono.fromCallable(() -> {
			Object rawCommand = arguments == null ? null : arguments.get("command");
			if (!(rawCommand instanceof String command) || command.isBlank()) {
				return McpSchema.CallToolResult.builder()
						.addTextContent("Error: 'command' parameter is required and must be a non-empty string")
						.isError(true)
						.build();
			}

			CappedOutputStream out = new CappedOutputStream(MAX_OUTPUT_BYTES);
			CappedOutputStream err = new CappedOutputStream(MAX_OUTPUT_BYTES);
			InputStream in = new ByteArrayInputStream(new byte[0]);

			try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
				 PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {

				CommandSession session = commandProcessor.createSession(in, outStream, errStream);
				try {
					Object result = session.execute(command);

					String output = out.toUtf8();
					String error = err.toUtf8();

					StringBuilder sb = new StringBuilder();
					sb.append("**Command:** `").append(command).append("`\n\n");

					if (!output.isEmpty()) {
						sb.append(output);
					}
					if (result != null && output.isEmpty()) {
						sb.append(result.toString());
					}
					if (!error.isEmpty()) {
						sb.append("\n**Stderr:**\n").append(error);
					}
					if (out.isTruncated() || err.isTruncated()) {
						sb.append(String.format("%n_[output truncated at %d bytes]_", MAX_OUTPUT_BYTES));
					}

					return McpSchema.CallToolResult.builder()
							.addTextContent(sb.toString())
							.build();
				} finally {
					session.close();
				}
			}
		}).onErrorResume(e -> Mono.just(
				McpSchema.CallToolResult.builder()
						.addTextContent("Error executing Gogo command: " + e.getMessage())
						.isError(true)
						.build()));
	}
}
