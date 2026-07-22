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

import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EParameter;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.EcoreAuthoring;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP authoring tool adding an {@link EParameter} to an EOperation.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEParameterTool", service = MCPTool.class, property = "tool.name=add_eparameter")
public class AddEParameterTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "add_eparameter";
		this.description = "Add an EParameter to an EOperation (see add_eoperation). 'eType' is a classifier ref "
				+ "or dataset objectId. Multiplicity via lowerBound/upperBound (-1 = unbounded).";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"operationObjectId": { "type": "string" },
						"name": { "type": "string" },
						"eType": { "type": "string", "description": "classifier ref or dataset objectId (or use eGenericType)" },
						"eGenericType": { "type": "object", "description": "generic type spec; alternative to eType" },
						"lowerBound": { "type": "integer", "description": "default 0" },
						"upperBound": { "type": "integer", "description": "default 1; -1 = unbounded" },
						"ordered": { "type": "boolean", "description": "default true" },
						"unique": { "type": "boolean", "description": "default true" }
					},
					"required": ["datasetId", "operationObjectId", "name"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			Dataset dataset = registry.require(sessionId, requireString(arguments, "datasetId"));
			EOperation operation = EcoreAuthoring.requireEOperation(dataset, requireString(arguments, "operationObjectId"));
			EParameter parameter = EcoreFactory.eINSTANCE.createEParameter();
			parameter.setName(requireString(arguments, "name"));
			EcoreAuthoring.applyType(parameter, optionalString(arguments, "eType"), arguments.get("eGenericType"), true, dataset, guard.resolverFor(sessionId));
			Integer lower = optionalInt(arguments, "lowerBound");
			Integer upper = optionalInt(arguments, "upperBound");
			parameter.setLowerBound(lower == null ? 0 : lower);
			parameter.setUpperBound(upper == null ? 1 : upper);
			parameter.setOrdered(optionalBoolean(arguments, "ordered", true));
			parameter.setUnique(optionalBoolean(arguments, "unique", true));
			operation.getEParameters().add(parameter);
			String objectId = EcoreAuthoring.put(dataset, parameter, registry.limits());
			return Map.of("objectId", objectId, "name", parameter.getName(), "objectCount", dataset.objectCount());
		});
	}
}
