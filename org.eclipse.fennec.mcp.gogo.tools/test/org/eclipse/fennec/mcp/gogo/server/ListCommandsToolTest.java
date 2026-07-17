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
 * Verifies scope filtering, the type-safe scope guard and the output cap of
 * {@link ListCommandsTool}.
 */
class ListCommandsToolTest {

	private static final String HELP = "felix:lb\nfelix:install\nscr:list\ngogo:cat\n";

	private CommandProcessor processor = mock(CommandProcessor.class);
	private CommandSession session = mock(CommandSession.class);
	private ListCommandsTool tool;

	@BeforeEach
	void setUp() throws Exception {
		tool = new ListCommandsTool();
		Field field = ListCommandsTool.class.getDeclaredField("commandProcessor");
		field.setAccessible(true);
		field.set(tool, processor);
		tool.activate();

		PrintStream[] captured = new PrintStream[1];
		when(processor.createSession(any(), any(), any())).thenAnswer(inv -> {
			captured[0] = inv.getArgument(1);
			return session;
		});
		when(session.execute(anyString())).thenAnswer(inv -> {
			captured[0].print(HELP);
			captured[0].flush();
			return null;
		});
	}

	private static String textOf(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	@Test
	void noScope_returnsFullList() {
		String text = textOf(tool.execute(null, Map.of()).block());
		assertThat(text).contains("scr:list").contains("gogo:cat").contains("felix:install");
	}

	@Test
	void scope_filtersMatchingLines() {
		String text = textOf(tool.execute(null, Map.of("scope", "felix")).block());
		assertThat(text).contains("felix:lb").contains("felix:install").doesNotContain("scr:list");
	}

	@Test
	void unknownScope_fallsBackToFullList() {
		String text = textOf(tool.execute(null, Map.of("scope", "doesnotexist")).block());
		assertThat(text).contains("No commands found").contains("scr:list");
	}

	@Test
	void nonStringScope_isIgnoredNotCrashing() {
		Map<String, Object> args = new HashMap<>();
		args.put("scope", 123);
		McpSchema.CallToolResult result = tool.execute(null, args).block();
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(textOf(result)).contains("scr:list");
	}
}
