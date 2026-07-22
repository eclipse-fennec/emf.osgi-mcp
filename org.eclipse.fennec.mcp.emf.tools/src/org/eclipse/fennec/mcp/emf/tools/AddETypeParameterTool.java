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

import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.ETypeParameter;
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
 * MCP authoring tool declaring an {@link ETypeParameter} on an EClass, EDataType
 * or EOperation. Reference the declared parameter from a generic type via its
 * returned objectId ({@code {"typeParameter": "<objectId>"}}).
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddETypeParameterTool", service = MCPTool.class, property = "tool.name=add_etypeparameter")
public class AddETypeParameterTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "add_etypeparameter";
		this.description = "Declare a generic type parameter on an EClass, EDataType or EOperation. 'eBounds' are "
				+ "generic types (upper bounds). Returns the objectId; reference it from a generic type as "
				+ "{\\\"typeParameter\\\": \\\"<objectId>\\\"}.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"ownerObjectId": { "type": "string", "description": "objectId of an EClass, EDataType or EOperation" },
						"name": { "type": "string" },
						"eBounds": { "type": "array", "items": { "type": "object" }, "description": "upper-bound generic types" }
					},
					"required": ["datasetId", "ownerObjectId", "name"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			Dataset dataset = registry.require(sessionId, requireString(arguments, "datasetId"));
			EObject owner = dataset.requireObject(requireString(arguments, "ownerObjectId"));
			ClassifierResolver resolver = guard.resolverFor(sessionId);

			ETypeParameter typeParameter = EcoreFactory.eINSTANCE.createETypeParameter();
			typeParameter.setName(requireString(arguments, "name"));
			Object bounds = arguments.get("eBounds");
			if (bounds != null) {
				if (!(bounds instanceof List<?> list)) {
					throw new ToolException("'eBounds' must be an array of generic types");
				}
				for (Object bound : list) {
					typeParameter.getEBounds().add(GenericTypes.parse(dataset, resolver, bound));
				}
			}
			addTo(owner, typeParameter);
			String objectId = EcoreAuthoring.put(dataset, typeParameter, registry.limits());
			return Map.of("objectId", objectId, "name", typeParameter.getName(), "objectCount", dataset.objectCount());
		});
	}

	private static void addTo(EObject owner, ETypeParameter typeParameter) {
		if (owner instanceof EClassifier classifier) {
			classifier.getETypeParameters().add(typeParameter);
		} else if (owner instanceof EOperation operation) {
			operation.getETypeParameters().add(typeParameter);
		} else {
			throw new ToolException(String.format("Owner is a %s; type parameters are declared on an EClass, EDataType or EOperation", owner.eClass().getName()));
		}
	}
}
