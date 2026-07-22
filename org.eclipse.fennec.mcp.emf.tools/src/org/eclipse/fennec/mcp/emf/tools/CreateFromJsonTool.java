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

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.FromJsonSupport;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.RecipeOp;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool creating a complete instance graph declaratively from one JSON
 * payload. The graph lands in a new dataset (so it remains modifiable with
 * modify_feature) and the payload is recorded as a replayable {@code fromJson}
 * recipe operation. Returns a lightweight ack — use {@code export_dataset}
 * for the serialized content.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "CreateFromJsonTool", service = MCPTool.class, property = "tool.name=create_from_json")
public class CreateFromJsonTool extends AbstractEMFTool {

	@Reference
	ModelGuard guard;
	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "create_from_json";
		this.description = "Create a complete instance graph from one JSON object in a single call. " +
				"The JSON structure must match the EClass features (see describe_eclass); containment " +
				"features take nested objects. The graph is stored as a new modifiable dataset. " +
				"Use export_dataset to obtain the XMI.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"eClass": {
							"type": "string",
							"description": "The root EClass identifier of the form <nsURI>#//<ClassName>"
						},
						"data": {
							"type": "object",
							"description": "The instance data; nested objects populate containment features"
						},
						"seed": {
							"type": "integer",
							"description": "Optional reproducibility seed stored with the dataset"
						}
					},
					"required": ["eClass", "data"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			EClass eClass = guard.resolverFor(sessionId).resolveConcreteEClass(requireString(arguments, "eClass"));
			Object data = arguments.get("data");
			if (!(data instanceof Map)) {
				throw new ToolException("Parameter 'data' is required and must be a JSON object");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> dataMap = (Map<String, Object>) data;
			Dataset dataset = registry.create(sessionId, optionalLong(arguments, "seed"));
			String objectId = dataset.nextObjectId();
			FromJsonSupport.load(dataset, objectId, eClass, dataMap, registry.limits());
			dataset.record(RecipeOp.fromJson(objectId, ModelGuard.refOf(eClass), dataMap));
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("datasetId", dataset.getId());
			result.put("objectId", objectId);
			result.put("objectCount", dataset.objectCount());
			result.put("hint", "Use export_dataset for XMI, modify_feature to adjust, inspect_dataset for validation");
			return result;
		});
	}
}
