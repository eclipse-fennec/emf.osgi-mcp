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
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.EcoreAuthoring;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP authoring tool adding an {@link EReference} to an EClass. The type is a
 * class reference; {@code eOpposite} and {@code eKeys} are objectIds of features
 * authored in the same dataset.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEReferenceTool", service = MCPTool.class, property = "tool.name=add_ereference")
public class AddEReferenceTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "add_ereference";
		this.description = "Add an EReference to an EClass. 'eType' is a class ref <nsURI>#//<Name> or a dataset "
				+ "objectId. 'containment' makes it own its targets. 'eOpposite' is the objectId of the opposite "
				+ "EReference; 'eKeys' are objectIds of key EAttributes. Multiplicity via lowerBound/upperBound (-1 = unbounded).";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"classObjectId": { "type": "string" },
						"name": { "type": "string" },
						"eType": { "type": "string", "description": "class ref <nsURI>#//<Name> or dataset objectId" },
						"containment": { "type": "boolean", "description": "default false" },
						"resolveProxies": { "type": "boolean", "description": "default true" },
						"eOpposite": { "type": "string", "description": "objectId of the opposite EReference" },
						"eKeys": { "type": "array", "items": { "type": "string" }, "description": "objectIds of key EAttributes" },
						"lowerBound": { "type": "integer", "description": "default 0" },
						"upperBound": { "type": "integer", "description": "default 1; -1 = unbounded" },
						"changeable": { "type": "boolean" },
						"ordered": { "type": "boolean" },
						"unique": { "type": "boolean" },
						"transient": { "type": "boolean" },
						"volatile": { "type": "boolean" },
						"unsettable": { "type": "boolean" },
						"derived": { "type": "boolean" }
					},
					"required": ["datasetId", "classObjectId", "name", "eType"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			Dataset dataset = registry.require(sessionId, requireString(arguments, "datasetId"));
			EClass owner = EcoreAuthoring.requireEClass(dataset, requireString(arguments, "classObjectId"));
			EReference reference = EcoreFactory.eINSTANCE.createEReference();
			reference.setName(requireString(arguments, "name"));
			reference.setEType(EcoreAuthoring.resolveEClass(dataset, guard.resolverFor(sessionId), requireString(arguments, "eType")));
			EcoreAuthoring.applyFlags(reference, featureFlags(arguments));
			reference.setContainment(optionalBoolean(arguments, "containment", false));
			reference.setResolveProxies(optionalBoolean(arguments, "resolveProxies", true));
			String oppositeId = optionalString(arguments, "eOpposite");
			if (oppositeId != null) {
				EObject opposite = dataset.requireObject(oppositeId);
				if (!(opposite instanceof EReference oppositeRef)) {
					throw new ToolException(String.format("eOpposite '%s' is a %s, not an EReference", oppositeId, opposite.eClass().getName()));
				}
				reference.setEOpposite(oppositeRef);
			}
			for (String keyId : optionalStringList(arguments, "eKeys")) {
				EObject key = dataset.requireObject(keyId);
				if (!(key instanceof org.eclipse.emf.ecore.EAttribute keyAttr)) {
					throw new ToolException(String.format("eKey '%s' is a %s, not an EAttribute", keyId, key.eClass().getName()));
				}
				reference.getEKeys().add(keyAttr);
			}
			owner.getEStructuralFeatures().add(reference);
			String objectId = EcoreAuthoring.put(dataset, reference, registry.limits());
			return Map.of("objectId", objectId, "name", reference.getName(), "objectCount", dataset.objectCount());
		});
	}
}
