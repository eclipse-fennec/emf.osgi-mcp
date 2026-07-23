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
package org.eclipse.fennec.mcp.service.tools;

import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.service.api.ServiceClient;
import org.eclipse.fennec.service.api.ServiceInvocationException;
import org.eclipse.fennec.service.api.ServiceOperation;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * One MCP tool wrapping one {@link ServiceOperation} of a bound
 * {@link ServiceClient}. Schemas are precomputed at registration time by the
 * bridge (the imported EClasses are immutable); execution converts the
 * argument map to the request {@link EObject} via the codec, invokes the
 * client and serializes the response back to JSON. A
 * {@link ServiceInvocationException} becomes an {@code isError} result with
 * the message only — no stack traces reach the model.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
class ServiceOperationTool implements MCPTool {

	/**
	 * Conversion seam between the MCP argument map and the EMF request/response
	 * objects; implemented with the Fennec codec by the bridge, mockable in tests.
	 */
	interface PayloadCodec {

		/** @return the request object built from the arguments, never {@code null} */
		EObject toRequest(ServiceOperation operation, Map<String, Object> arguments);

		/** @return the JSON serialization of the response object */
		String toJson(EObject response);
	}

	private final String name;
	private final String description;
	private final String inputSchema;
	private final String outputSchema;
	private final ServiceClient client;
	private final ServiceOperation operation;
	private final PayloadCodec codec;

	ServiceOperationTool(String name, String description, String inputSchema, String outputSchema,
			ServiceClient client, ServiceOperation operation, PayloadCodec codec) {
		this.name = name;
		this.description = description;
		this.inputSchema = inputSchema;
		this.outputSchema = outputSchema;
		this.client = client;
		this.operation = operation;
		this.codec = codec;
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
		return outputSchema;
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return Mono.fromCallable(() -> invoke(arguments));
	}

	private McpSchema.CallToolResult invoke(Map<String, Object> arguments) {
		try {
			EObject request = operation.requestType() == null ? null
					: codec.toRequest(operation, arguments == null ? Map.of() : arguments);
			EObject response = client.invoke(operation, request);
			String json = response == null ? "{}" : codec.toJson(response);
			return McpSchema.CallToolResult.builder().addTextContent(json).build();
		} catch (ServiceInvocationException e) {
			return McpSchema.CallToolResult.builder()
					.addTextContent(String.format("Service invocation failed: %s", e.getMessage()))
					.isError(true)
					.build();
		}
	}
}
