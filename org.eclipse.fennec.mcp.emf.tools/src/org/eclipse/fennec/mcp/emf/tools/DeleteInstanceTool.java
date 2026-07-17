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

import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ModelOperations;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool deleting an object (including its containment subtree) from a
 * dataset, removing all references to the deleted objects.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "DeleteInstanceTool", service = MCPTool.class, property = "tool.name=delete_instance")
public class DeleteInstanceTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "delete_instance";
		this.description = "Delete an object from a dataset. The object's containment children are " +
				"deleted as well, and all references to the deleted objects are cleared.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": {
							"type": "string",
							"description": "The dataset containing the object"
						},
						"objectId": {
							"type": "string",
							"description": "The object to delete"
						}
					},
					"required": ["datasetId", "objectId"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Dataset dataset = registry.require(sessionId(exchange), requireString(arguments, "datasetId"));
			String objectId = requireString(arguments, "objectId");
			ModelOperations.deleteInstance(dataset, objectId, registry.limits());
			return Map.of(
					"deleted", objectId,
					"objectCount", dataset.objectCount());
		});
	}
}
