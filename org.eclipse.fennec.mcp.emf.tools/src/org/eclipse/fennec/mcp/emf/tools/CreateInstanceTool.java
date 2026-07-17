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
package org.eclipse.fennec.mcp.emf.tools;

import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.ModelOperations;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool creating a new instance of an allow-listed EClass inside a dataset.
 * Returns only a lightweight ack ({@code objectId}) — payload is never echoed.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "CreateInstanceTool", service = MCPTool.class, property = "tool.name=create_instance")
public class CreateInstanceTool extends AbstractEMFTool {

	@Reference
	ModelGuard guard;
	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "create_instance";
		this.description = "Create a new instance of an EClass inside a dataset. Returns the objectId " +
				"used to address the instance in modify_feature calls. The instance starts with default " +
				"values; populate it with modify_feature.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": {
							"type": "string",
							"description": "The dataset to create the instance in (see create_dataset)"
						},
						"eClass": {
							"type": "string",
							"description": "The EClass identifier of the form <nsURI>#//<ClassName>"
						}
					},
					"required": ["datasetId", "eClass"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Dataset dataset = registry.require(sessionId(exchange), requireString(arguments, "datasetId"));
			EClass eClass = guard.requireAllowedEClass(requireString(arguments, "eClass"));
			String objectId = ModelOperations.createInstance(dataset, eClass, registry.limits());
			return Map.of(
					"objectId", objectId,
					"eClass", ModelGuard.refOf(eClass),
					"objectCount", dataset.objectCount());
		});
	}
}
