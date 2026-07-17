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

import java.util.List;
import java.util.Map;

import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.FromJsonSupport;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.ModelOperations;
import org.eclipse.fennec.mcp.emf.tools.core.RecipeOp;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool managing a dataset as a whole: {@code regenerate} (clear and
 * deterministically replay the recorded recipe — no LLM involved),
 * {@code clear} (drop objects and recipe) and {@code delete}.
 * Recipe replay re-validates every operation against the current allow-list.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "ManageDatasetTool", service = MCPTool.class, property = "tool.name=manage_dataset")
public class ManageDatasetTool extends AbstractEMFTool {

	@Reference
	ModelGuard guard;
	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "manage_dataset";
		this.description = "Manage a dataset: 'regenerate' clears the objects and deterministically " +
				"replays the recorded build recipe (reproducible, no LLM involved), 'clear' drops all " +
				"objects and the recipe, 'delete' removes the dataset entirely.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": {
							"type": "string",
							"description": "The dataset to manage"
						},
						"action": {
							"type": "string",
							"enum": ["regenerate", "clear", "delete"],
							"description": "The management action"
						}
					},
					"required": ["datasetId", "action"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			String datasetId = requireString(arguments, "datasetId");
			String action = requireString(arguments, "action");
			switch (action) {
			case "regenerate" -> {
				Dataset dataset = registry.require(sessionId, datasetId);
				List<RecipeOp> recipe = dataset.recipeSnapshot();
				dataset.clearObjects();
				ModelOperations.replay(dataset, recipe, guard, registry.limits(),
						(ds, objectId, eClass, data) -> FromJsonSupport.load(ds, objectId, eClass, data, registry.limits()));
				return Map.of(
						"datasetId", datasetId,
						"action", action,
						"objectCount", dataset.objectCount(),
						"replayedOps", recipe.size());
			}
			case "clear" -> {
				Dataset dataset = registry.require(sessionId, datasetId);
				dataset.reset();
				return Map.of("datasetId", datasetId, "action", action, "objectCount", 0);
			}
			case "delete" -> {
				if (!registry.delete(sessionId, datasetId)) {
					throw new ToolException(String.format("Unknown datasetId '%s' in this session", datasetId));
				}
				return Map.of("datasetId", datasetId, "action", action, "deleted", true);
			}
			default -> throw new ToolException(String.format("Unknown action '%s'. Use one of: regenerate, clear, delete", action));
			}
		});
	}
}
