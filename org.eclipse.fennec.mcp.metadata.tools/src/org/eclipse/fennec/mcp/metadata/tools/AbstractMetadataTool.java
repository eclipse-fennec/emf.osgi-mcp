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

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.fennec.mcp.api.AbstractMCPTool;
import org.eclipse.fennec.mcp.metadata.tools.core.ToolException;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class of the metadata MCP tools. Provides JSON result rendering, argument
 * helpers and sanitized error handling: {@link ToolException} messages are
 * returned to the agent verbatim (they are written for self-correction), any
 * other exception is logged server-side and mapped to a generic error so
 * internals never leak to the client.
 * <p>
 * Unlike the EMF model tools these are session-independent — the metadata layer
 * is runtime-wide — so there is no session id resolution here.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
public abstract class AbstractMetadataTool extends AbstractMCPTool {

	private static final Logger LOGGER = Logger.getLogger(AbstractMetadataTool.class.getName());

	protected static final JsonMapper MAPPER = JsonMapper.builder()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.build();

	/**
	 * Runs the tool body on a reactive callable, mapping exceptions to sanitized
	 * MCP error results.
	 *
	 * @param body the tool body returning either a String (verbatim text) or any JSON-mappable object
	 * @return the call result mono
	 */
	protected Mono<McpSchema.CallToolResult> run(Callable<Object> body) {
		return Mono.fromCallable(() -> {
			try {
				Object result = body.call();
				String text = result instanceof String string ? string : MAPPER.writeValueAsString(result);
				return McpSchema.CallToolResult.builder().addTextContent(text).build();
			} catch (ToolException e) {
				return error(e.getMessage());
			} catch (Exception e) {
				LOGGER.log(Level.SEVERE, e, () -> String.format("Unexpected error executing MCP tool '%s'", getName()));
				return error("Unexpected server error while executing " + getName() + " — see the server log for details");
			}
		});
	}

	/**
	 * @param message the sanitized error message
	 * @return an MCP error result carrying the message
	 */
	protected static McpSchema.CallToolResult error(String message) {
		return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
	}

	protected static String requireString(Map<String, Object> arguments, String key) {
		Object value = arguments == null ? null : arguments.get(key);
		if (!(value instanceof String string) || string.isBlank()) {
			throw new ToolException(String.format("Parameter '%s' is required and must be a non-empty string", key));
		}
		return string;
	}

	protected static String optionalString(Map<String, Object> arguments, String key) {
		Object value = arguments == null ? null : arguments.get(key);
		return value instanceof String string && !string.isBlank() ? string : null;
	}
}
