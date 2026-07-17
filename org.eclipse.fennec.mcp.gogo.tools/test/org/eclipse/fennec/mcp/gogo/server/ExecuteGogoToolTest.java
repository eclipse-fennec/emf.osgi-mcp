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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Verifies argument validation, output relaying, session cleanup and the OOM
 * guard (output cap) of {@link ExecuteGogoTool}.
 */
class ExecuteGogoToolTest {

	private CommandProcessor processor = mock(CommandProcessor.class);
	private CommandSession session = mock(CommandSession.class);
	private ExecuteGogoTool tool;

	@BeforeEach
	void setUp() throws Exception {
		tool = new ExecuteGogoTool();
		Field field = ExecuteGogoTool.class.getDeclaredField("commandProcessor");
		field.setAccessible(true);
		field.set(tool, processor);
		tool.activate();
	}

	/** Stubs the session so that {@code execute} writes the given text to the captured stdout stream. */
	private void stubOutput(String output) throws Exception {
		PrintStream[] captured = new PrintStream[1];
		when(processor.createSession(any(), any(), any())).thenAnswer(inv -> {
			captured[0] = inv.getArgument(1);
			return session;
		});
		when(session.execute(anyString())).thenAnswer(inv -> {
			captured[0].print(output);
			captured[0].flush();
			return null;
		});
	}

	private static String textOf(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	@Test
	void nullArguments_returnsError() {
		McpSchema.CallToolResult result = tool.execute(null, null).block();
		assertThat(result.isError()).isEqualTo(Boolean.TRUE);
		assertThat(textOf(result)).contains("command");
	}

	@Test
	void nonStringCommand_returnsError() {
		Map<String, Object> args = new HashMap<>();
		args.put("command", 42);
		McpSchema.CallToolResult result = tool.execute(null, args).block();
		assertThat(result.isError()).isEqualTo(Boolean.TRUE);
		assertThat(textOf(result)).contains("non-empty string");
	}

	@Test
	void blankCommand_returnsError() {
		McpSchema.CallToolResult result = tool.execute(null, Map.of("command", "   ")).block();
		assertThat(result.isError()).isEqualTo(Boolean.TRUE);
	}

	@Test
	void validCommand_relaysOutputAndClosesSession() throws Exception {
		stubOutput("bundle 0|System Bundle");
		McpSchema.CallToolResult result = tool.execute(null, Map.of("command", "lb")).block();

		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(textOf(result)).contains("**Command:** `lb`").contains("System Bundle");
		verify(session).close();
	}

	@Test
	void oversizedOutput_isTruncated() throws Exception {
		stubOutput("x".repeat(ExecuteGogoTool.MAX_OUTPUT_BYTES + 1024));
		McpSchema.CallToolResult result = tool.execute(null, Map.of("command", "cat /big")).block();

		String text = textOf(result);
		assertThat(text).contains("output truncated");
		// echoed command + notice, but bounded to roughly the cap rather than the full payload
		assertThat(text.length()).isLessThan(ExecuteGogoTool.MAX_OUTPUT_BYTES + 512);
	}
}
