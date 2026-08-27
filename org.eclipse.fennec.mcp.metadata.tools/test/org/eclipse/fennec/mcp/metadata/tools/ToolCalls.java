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
package org.eclipse.fennec.mcp.metadata.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invokes an MCP tool and reads its JSON payload back. The tools are
 * session-independent, so no exchange is needed and {@code null} is passed.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
final class ToolCalls {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private ToolCalls() {
		// static helpers
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> call(AbstractMetadataTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(null, arguments).block();
		assertThat(result).isNotNull();
		String text = text(result);
		assertThat(result.isError()).as("tool error: %s", text).isNotEqualTo(Boolean.TRUE);
		return MAPPER.readValue(text, Map.class);
	}

	static String callExpectingError(AbstractMetadataTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(null, arguments).block();
		assertThat(result).isNotNull();
		assertThat(result.isError()).isEqualTo(Boolean.TRUE);
		return text(result);
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> list(Map<String, Object> result, String key) {
		return (List<Map<String, Object>>) result.get(key);
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Map<String, Object> result, String key) {
		return (Map<String, Object>) result.get(key);
	}

	@SuppressWarnings("unchecked")
	static List<String> strings(Map<String, Object> result, String key) {
		return (List<String>) result.get(key);
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}
}
