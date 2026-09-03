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
 * MCP tool deterministically replaying a build recipe (as returned by
 * {@code inspect_dataset} with {@code includeRecipe=true}) into a fresh
 * dataset — byte-identical reproduction without involving an LLM. Every
 * operation is re-validated against the current allow-list; a recipe cannot
 * bypass the security policy.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "ReplayRecipeTool", service = MCPTool.class, property = "tool.name=replay_recipe")
public class ReplayRecipeTool extends AbstractEMFTool {

	@Reference
	ModelGuard guard;
	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "replay_recipe";
		this.description = "Deterministically replay a build recipe into a new dataset. A recipe is the " +
				"ordered operation log returned by inspect_dataset with includeRecipe=true; replaying it " +
				"reproduces the identical dataset (and identical XMI) without any LLM involvement. " +
				"All operations are re-checked against the current allow-list.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"recipe": {
							"type": "array",
							"items": { "type": "object" },
							"description": "The ordered recipe operations, e.g. [{\\"op\\":\\"create\\",\\"objectId\\":\\"o1\\",\\"eClass\\":\\"...\\"}, {\\"op\\":\\"set\\",\\"objectId\\":\\"o1\\",\\"feature\\":\\"name\\",\\"value\\":\\"x\\"}]"
						},
						"seed": {
							"type": "integer",
							"description": "Optional reproducibility seed stored with the new dataset"
						}
					},
					"required": ["recipe"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Object recipeArg = arguments == null ? null : arguments.get("recipe");
			if (!(recipeArg instanceof List<?> rawOps) || rawOps.isEmpty()) {
				throw new ToolException("Parameter 'recipe' is required and must be a non-empty array of operations");
			}
			if (rawOps.size() > registry.limits().maxRecipeOps()) {
				throw new ToolException(String.format("Recipe exceeds the limit of %d operations", registry.limits().maxRecipeOps()));
			}
			List<RecipeOp> recipe = new ArrayList<>(rawOps.size());
			for (Object rawOp : rawOps) {
				if (!(rawOp instanceof Map<?, ?> opMap)) {
					throw new ToolException("Each recipe entry must be a JSON object");
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> typedOp = (Map<String, Object>) opMap;
				recipe.add(RecipeOp.fromMap(typedOp));
			}
			String sessionId = sessionId(exchange);
			Dataset dataset = registry.create(sessionId, optionalLong(arguments, "seed"));
			ModelOperations.replay(dataset, recipe, guard.resolverFor(sessionId), registry.limits(),
					(ds, objectId, eClass, data) -> FromJsonSupport.loadAndWarn(ds, objectId, eClass, data, registry.limits()));
			recipe.forEach(dataset::record);
			return Map.of(
					"datasetId", dataset.getId(),
					"objectCount", dataset.objectCount(),
					"replayedOps", recipe.size(),
					"hint", "Use export_dataset for XMI; the new dataset is modifiable like any other");
		});
	}
}
