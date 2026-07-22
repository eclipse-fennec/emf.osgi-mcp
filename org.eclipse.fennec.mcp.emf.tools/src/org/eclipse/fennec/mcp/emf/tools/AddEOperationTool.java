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
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.ClassifierResolver;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.EcoreAuthoring;
import org.eclipse.fennec.mcp.emf.tools.core.GenericTypes;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP authoring tool adding an {@link EOperation} to an EClass. Add parameters
 * with add_eparameter. Omit 'eType' for a void operation.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEOperationTool", service = MCPTool.class, property = "tool.name=add_eoperation")
public class AddEOperationTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "add_eoperation";
		this.description = "Add an EOperation to an EClass. Omit 'eType' for void; 'eExceptions' are classifier "
				+ "refs. Multiplicity of the return type via lowerBound/upperBound (-1 = unbounded). Add "
				+ "parameters with add_eparameter.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"classObjectId": { "type": "string" },
						"name": { "type": "string" },
						"eType": { "type": "string", "description": "return type ref or dataset objectId; omit for void (or use eGenericType)" },
						"eGenericType": { "type": "object", "description": "generic return type spec; alternative to eType" },
						"lowerBound": { "type": "integer", "description": "default 0" },
						"upperBound": { "type": "integer", "description": "default 1; -1 = unbounded" },
						"ordered": { "type": "boolean", "description": "default true" },
						"unique": { "type": "boolean", "description": "default true" },
						"eExceptions": { "type": "array", "items": { "type": "string" }, "description": "classifier refs" },
						"eGenericExceptions": { "type": "array", "items": { "type": "object" }, "description": "generic type specs" }
					},
					"required": ["datasetId", "classObjectId", "name"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			Dataset dataset = registry.require(sessionId, requireString(arguments, "datasetId"));
			EClass owner = EcoreAuthoring.requireEClass(dataset, requireString(arguments, "classObjectId"));
			ClassifierResolver resolver = guard.resolverFor(sessionId);
			EOperation operation = EcoreFactory.eINSTANCE.createEOperation();
			operation.setName(requireString(arguments, "name"));
			EcoreAuthoring.applyType(operation, optionalString(arguments, "eType"), arguments.get("eGenericType"), false, dataset, resolver);
			Integer lower = optionalInt(arguments, "lowerBound");
			Integer upper = optionalInt(arguments, "upperBound");
			operation.setLowerBound(lower == null ? 0 : lower);
			operation.setUpperBound(upper == null ? 1 : upper);
			operation.setOrdered(optionalBoolean(arguments, "ordered", true));
			operation.setUnique(optionalBoolean(arguments, "unique", true));
			for (String exceptionRef : optionalStringList(arguments, "eExceptions")) {
				operation.getEExceptions().add(EcoreAuthoring.resolveClassifier(dataset, resolver, exceptionRef));
			}
			Object genericExceptions = arguments.get("eGenericExceptions");
			if (genericExceptions != null) {
				if (!(genericExceptions instanceof java.util.List<?> list)) {
					throw new ToolException("'eGenericExceptions' must be an array of generic types");
				}
				for (Object spec : list) {
					operation.getEGenericExceptions().add(GenericTypes.parse(dataset, resolver, spec));
				}
			}
			owner.getEOperations().add(operation);
			String objectId = EcoreAuthoring.put(dataset, operation, registry.limits());
			return Map.of("objectId", objectId, "name", operation.getName(), "objectCount", dataset.objectCount());
		});
	}
}
