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
import org.eclipse.fennec.mcp.emf.tools.core.JsonLoadReport;
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
 * recipe operation. Returns an ack plus the {@link JsonLoadReport} of the load —
 * use {@code export_dataset} for the serialized content, not to find out whether
 * the payload arrived intact.
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
				"The result carries a 'coverage' report saying which keys of your payload matched no " +
				"feature ('unmatchedPaths') and which matched one that stayed empty ('droppedPaths') — so " +
				"when you are checking a sample against a metamodel you inferred, read that report rather " +
				"than exporting the XMI: 'complete': true means every key landed. A non-empty " +
				"'unmatchedPaths' means the metamodel is missing a feature the sample carries. Set " +
				"strict=true to have the call fail instead of reporting. Use export_dataset only when you " +
				"actually want the serialization itself.";
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
						},
						"strict": {
							"type": "boolean",
							"description": "Refuse the call if any key of 'data' matches no structural feature (default false). Use it while validating an inferred metamodel against samples; leave it off when the payload deliberately carries envelope fields you do not model."
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
			JsonLoadReport coverage = FromJsonSupport.load(dataset, objectId, eClass, dataMap, registry.limits());
			if (optionalBoolean(arguments, "strict", false) && !coverage.unmatchedPaths().isEmpty()) {
				// A refused call must leave nothing behind, or the agent has to clean up
				// a dataset it was never told the id of.
				registry.delete(sessionId, dataset.getId());
				throw new ToolException(String.format("%s. Add the missing features to '%s' with add_eattribute "
						+ "or add_ereference, or drop strict to load the payload as it is.",
						coverage.describeUnmatched(), eClass.getName()));
			}
			dataset.record(RecipeOp.fromJson(objectId, ModelGuard.refOf(eClass), dataMap));
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("datasetId", dataset.getId());
			result.put("objectId", objectId);
			result.put("objectCount", dataset.objectCount());
			result.put("coverage", coverage.toMap());
			result.put("hint", coverage.isComplete()
					? "Every payload key landed; export_dataset is only needed for the serialization itself"
					: "Payload keys did not land — see coverage.unmatchedPaths; add the missing features and reload");
			return result;
		});
	}
}
