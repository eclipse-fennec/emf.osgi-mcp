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

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.EcoreAuthoring;
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

	@Activate
	void activate() {
		this.name = "create_epackage";
		this.description = "Create a new Ecore EPackage as a root of a dataset (metamodel authoring). "
				+ "Returns its objectId, used as packageObjectId when adding classifiers. Populate it with "
				+ "add_eclass/add_edatatype/add_eenum, then register_package and export_dataset (format=xmi).";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string", "description": "The dataset to create the package in (see create_dataset)" },
						"name": { "type": "string", "description": "The package name" },
						"nsURI": { "type": "string", "description": "The namespace URI (unique identifier of the package)" },
						"nsPrefix": { "type": "string", "description": "The namespace prefix" }
					},
					"required": ["datasetId", "name", "nsURI", "nsPrefix"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Dataset dataset = registry.require(sessionId(exchange), requireString(arguments, "datasetId"));
			EPackage ePackage = EcoreFactory.eINSTANCE.createEPackage();
			ePackage.setName(requireString(arguments, "name"));
			ePackage.setNsURI(requireString(arguments, "nsURI"));
			ePackage.setNsPrefix(requireString(arguments, "nsPrefix"));
			String objectId = EcoreAuthoring.put(dataset, ePackage, registry.limits());
			return Map.of("objectId", objectId, "nsURI", ePackage.getNsURI(), "objectCount", dataset.objectCount());
		});
	}
}
