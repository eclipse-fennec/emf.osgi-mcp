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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.Exports;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.RecipeOp;
import org.eclipse.fennec.mcp.emf.tools.core.ValidationReports;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool inspecting datasets of the current session. Without a
 * {@code datasetId} it lists all datasets; with one it returns the objects
 * (id, class, root flag), a validation summary and optionally the build
 * recipe. Never returns serialized payload — use {@code export_dataset}.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "InspectDatasetTool", service = MCPTool.class, property = "tool.name=inspect_dataset")
public class InspectDatasetTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "inspect_dataset";
		this.description = "Inspect datasets of this session. Without 'datasetId', lists all datasets. " +
				"With 'datasetId', returns the objects (objectId, eClass, root flag), a validation summary " +
				"and optionally the replayable build recipe (includeRecipe=true). " +
				"Use export_dataset to obtain the serialized content.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": {
							"type": "string",
							"description": "Optional dataset to inspect in detail. Omit to list all datasets of this session."
						},
						"includeRecipe": {
							"type": "boolean",
							"description": "Include the replayable build recipe in the response (default false)"
						}
					}
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			String datasetId = optionalString(arguments, "datasetId");
			if (datasetId == null) {
				List<Map<String, Object>> datasets = new ArrayList<>();
				for (Dataset dataset : registry.list(sessionId)) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("datasetId", dataset.getId());
					entry.put("objectCount", dataset.objectCount());
					entry.put("eClassCounts", Exports.eClassCounts(dataset));
					datasets.add(entry);
				}
				return Map.of("datasets", datasets);
			}
			Dataset dataset = registry.require(sessionId, datasetId);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("datasetId", dataset.getId());
			if (dataset.getSeed() != null) {
				result.put("seed", dataset.getSeed());
			}
			result.put("objectCount", dataset.objectCount());
			result.put("eClassCounts", Exports.eClassCounts(dataset));
			List<Map<String, Object>> objects = new ArrayList<>();
			for (Map.Entry<String, EObject> entry : dataset.objectsSnapshot().entrySet()) {
				Map<String, Object> object = new LinkedHashMap<>();
				object.put("objectId", entry.getKey());
				object.put("eClass", ModelGuard.refOf(entry.getValue().eClass()));
				object.put("root", entry.getValue().eContainer() == null);
				objects.add(object);
			}
			result.put("objects", objects);
			result.put("validation", ValidationReports.validate(dataset));
			if (optionalBoolean(arguments, "includeRecipe", false)) {
				result.put("recipe", dataset.recipeSnapshot().stream().map(RecipeOp::toMap).toList());
			}
			return result;
		});
	}
}
