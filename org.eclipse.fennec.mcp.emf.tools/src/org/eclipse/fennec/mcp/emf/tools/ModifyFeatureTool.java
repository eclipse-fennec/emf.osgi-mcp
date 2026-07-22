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
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import org.eclipse.fennec.mcp.emf.tools.core.ModelOperations;

/**
 * MCP tool modifying a single structural feature of a dataset object:
 * {@code set} and {@code unset} for single-valued features, {@code add} and
 * {@code remove} for many-valued features. Reference features take the
 * objectId of another object of the same dataset as value. Returns only a
 * lightweight ack — payload is never echoed.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "ModifyFeatureTool", service = MCPTool.class, property = "tool.name=modify_feature")
public class ModifyFeatureTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "modify_feature";
		this.description = "Modify one structural feature of an object in a dataset. Actions: " +
				"'set' (single-valued; value null unsets), 'unset', 'add' (append/insert into many-valued), " +
				"'remove' (by index or value from many-valued). For attribute features pass a JSON scalar; " +
				"for reference and containment features pass the objectId of another object in the same dataset. " +
				"Values are converted to the attribute's EDataType (enums by literal, dates as ISO strings).";
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
							"description": "The object to modify"
						},
						"feature": {
							"type": "string",
							"description": "The name of the structural feature (see describe_eclass)"
						},
						"action": {
							"type": "string",
							"enum": ["set", "unset", "add", "remove"],
							"description": "The modification to apply"
						},
						"value": {
							"description": "The attribute value (JSON scalar) or the objectId of a referenced object. Omit for 'unset' and index-based 'remove'."
						},
						"index": {
							"type": "integer",
							"description": "Optional list index for 'add' (insert position) and 'remove'"
						}
					},
					"required": ["datasetId", "objectId", "feature", "action"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Dataset dataset = registry.require(sessionId(exchange), requireString(arguments, "datasetId"));
			String objectId = requireString(arguments, "objectId");
			String feature = requireString(arguments, "feature");
			String action = requireString(arguments, "action");
			Object value = arguments.get("value");
			Integer index = optionalInt(arguments, "index");
			ModelOperations.modifyFeature(dataset, objectId, feature, action, value, index, registry.limits(),
					guard.resolverFor(sessionId(exchange)));
			return Map.of(
					"objectId", objectId,
					"feature", feature,
					"action", action,
					"recipeSize", dataset.recipeSize());
		});
	}
}
