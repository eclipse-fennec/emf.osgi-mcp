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

import org.eclipse.emf.ecore.EObject;
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
 * MCP authoring tool creating a new {@link EPackage} as a root of a dataset.
 * Populate it with add_eclass/add_edatatype/add_eenum, then register_package
 * to make it instantiable and export_dataset (format xmi) for the .ecore.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "CreateEPackageTool", service = MCPTool.class, property = "tool.name=create_epackage")
public class CreateEPackageTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "create_epackage";
		this.description = "Create a new Ecore EPackage as a root of a dataset (metamodel authoring). Declare the "
				+ "whole package in one call via 'eClassifiers' — classes with their features and annotations, enums "
				+ "with their literals — instead of waiting for each objectId in turn. Classifiers of the same call "
				+ "may reference each other as '#//<Name>' in any declaration order. Returns the package objectId "
				+ "(the packageObjectId of add_eclass and friends) and the objectId of every nested element. Then "
				+ "register_package and export_dataset (format=xmi).";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string", "description": "The dataset to create the package in (see create_dataset)" },
						"name": { "type": "string", "description": "The package name" },
						"nsURI": { "type": "string", "description": "The namespace URI (unique identifier of the package)" },
						"nsPrefix": { "type": "string", "description": "The namespace prefix" },
						"eClassifiers": {
							"type": "array",
							"items": { "type": "object" },
							"description": "classifiers to create in this package. Each entry has an 'eClass' of 'EClass' (default), 'EEnum' or 'EDataType' and otherwise the arguments of add_eclass / add_eenum / add_edatatype except datasetId/packageObjectId: an EClass takes eSuperTypes/abstract/interface plus nested eAttributes/eReferences/eAnnotations, an EEnum takes nested eLiterals. Types may reference a sibling of this package as '#//<Name>', in any order."
						}
					},
					"required": ["datasetId", "name", "nsURI", "nsPrefix"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			Dataset dataset = registry.require(sessionId, requireString(arguments, "datasetId"));
			EPackage ePackage = EcoreFactory.eINSTANCE.createEPackage();
			ePackage.setName(requireString(arguments, "name"));
			ePackage.setNsURI(requireString(arguments, "nsURI"));
			ePackage.setNsPrefix(requireString(arguments, "nsPrefix"));
			NestedAuthoring.applyClassifiers(dataset, ePackage, arguments, guard.resolverFor(sessionId));
			// nothing touched the dataset until here, so a failure above leaves it unchanged
			Map<String, EObject> indexed = EcoreAuthoring.indexTreeDetailed(dataset, ePackage, registry.limits(), EcoreAuthoring.ADDRESSABLE);
			String objectId = indexed.keySet().iterator().next();
			return Map.of("objectId", objectId, "nsURI", ePackage.getNsURI(),
					"created", NestedAuthoring.describe(indexed), "objectCount", dataset.objectCount());
		});
	}
}
