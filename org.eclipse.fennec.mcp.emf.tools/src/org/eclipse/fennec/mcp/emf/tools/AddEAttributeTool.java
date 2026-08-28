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

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
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
 * MCP authoring tool adding an {@link EAttribute} to an EClass. The type is a
 * datatype reference: a {@code <nsURI>#//<Name>} identifier (e.g. the built-in
 * {@code http://www.eclipse.org/emf/2002/Ecore#//EString}) or the objectId of a
 * datatype authored in the same dataset.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEAttributeTool", service = MCPTool.class, property = "tool.name=add_eattribute")
public class AddEAttributeTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "add_eattribute";
		this.description = "Add an EAttribute to an EClass. 'eType' is a datatype ref (e.g. "
				+ "'http://www.eclipse.org/emf/2002/Ecore#//EString') or a dataset objectId. Multiplicity via "
				+ "lowerBound/upperBound (-1 = unbounded). Flags default to the EMF generic editor defaults.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"classObjectId": { "type": "string" },
						"name": { "type": "string" },
						"eType": { "type": "string", "description": "datatype ref <nsURI>#//<Name>, '#//<Name>' for a sibling classifier of the owner's package, or a dataset objectId (or use eGenericType)" },
						"eGenericType": { "type": "object", "description": "generic type spec; alternative to eType" },
						"lowerBound": { "type": "integer", "description": "default 0" },
						"upperBound": { "type": "integer", "description": "default 1; -1 = unbounded" },
						"defaultValueLiteral": { "type": "string" },
						"iD": { "type": "boolean", "description": "default false" },
						"changeable": { "type": "boolean", "description": "default true" },
						"ordered": { "type": "boolean", "description": "default true" },
						"unique": { "type": "boolean", "description": "default true" },
						"transient": { "type": "boolean", "description": "default false" },
						"volatile": { "type": "boolean", "description": "default false" },
						"unsettable": { "type": "boolean", "description": "default false" },
						"derived": { "type": "boolean", "description": "default false" }
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
			EAttribute attribute = NestedAuthoring.buildEAttribute(dataset, arguments, NestedAuthoring.localTo(guard.resolverFor(sessionId), owner));
			owner.getEStructuralFeatures().add(attribute);
			String objectId = EcoreAuthoring.put(dataset, attribute, registry.limits());
			return Map.of("objectId", objectId, "name", attribute.getName(), "objectCount", dataset.objectCount());
		});
	}
}
