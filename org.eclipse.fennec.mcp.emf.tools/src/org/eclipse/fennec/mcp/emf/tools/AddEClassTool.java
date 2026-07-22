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
import org.eclipse.emf.ecore.EPackage;
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
 * MCP authoring tool adding an {@link EClass} to an EPackage. Long-tail
 * properties not covered here (e.g. changing name later) are settable via
 * modify_feature on the returned objectId; generics via add_etypeparameter.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEClassTool", service = MCPTool.class, property = "tool.name=add_eclass")
public class AddEClassTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "add_eclass";
		this.description = "Add an EClass to a package (metamodel authoring). Super types are references to "
				+ "other classifiers, each either a <nsURI>#//<Name> identifier or the objectId of a class "
				+ "authored in the same dataset. Returns the new class objectId; add features with "
				+ "add_eattribute/add_ereference/add_eoperation.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"packageObjectId": { "type": "string", "description": "objectId of the owning EPackage (see create_epackage)" },
						"name": { "type": "string" },
						"abstract": { "type": "boolean", "description": "default false" },
						"interface": { "type": "boolean", "description": "default false (implies abstract)" },
						"eSuperTypes": { "type": "array", "items": { "type": "string" }, "description": "classifier refs or dataset objectIds" }
					},
					"required": ["datasetId", "packageObjectId", "name"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			Dataset dataset = registry.require(sessionId, requireString(arguments, "datasetId"));
			EPackage ePackage = EcoreAuthoring.requireEPackage(dataset, requireString(arguments, "packageObjectId"));
			EClass eClass = EcoreFactory.eINSTANCE.createEClass();
			eClass.setName(requireString(arguments, "name"));
			boolean isInterface = optionalBoolean(arguments, "interface", false);
			eClass.setInterface(isInterface);
			eClass.setAbstract(isInterface || optionalBoolean(arguments, "abstract", false));
			EcoreAuthoring.addSuperTypes(dataset, eClass, optionalStringList(arguments, "eSuperTypes"), guard.resolverFor(sessionId));
			ePackage.getEClassifiers().add(eClass);
			String objectId = EcoreAuthoring.put(dataset, eClass, registry.limits());
			return Map.of("objectId", objectId, "eClass", ModelGuard.refOf(eClass), "objectCount", dataset.objectCount());
		});
	}
}
