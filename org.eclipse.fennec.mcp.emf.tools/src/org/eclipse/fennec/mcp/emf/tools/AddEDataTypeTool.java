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

import org.eclipse.emf.ecore.EDataType;
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
 * MCP authoring tool adding a (dynamic) {@link EDataType} to a package.
 * Registered packages must be dynamic, so instanceClassName/instanceTypeName is
 * intentionally not settable — authored datatypes serialize via String.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEDataTypeTool", service = MCPTool.class, property = "tool.name=add_edatatype")
public class AddEDataTypeTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "add_edatatype";
		this.description = "Add a dynamic EDataType to a package. Instance class names are not settable "
				+ "(registered packages must be dynamic); authored datatypes serialize as String.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"packageObjectId": { "type": "string" },
						"name": { "type": "string" },
						"serializable": { "type": "boolean", "description": "default true" }
					},
					"required": ["datasetId", "packageObjectId", "name"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Dataset dataset = registry.require(sessionId(exchange), requireString(arguments, "datasetId"));
			EPackage ePackage = EcoreAuthoring.requireEPackage(dataset, requireString(arguments, "packageObjectId"));
			EDataType eDataType = EcoreFactory.eINSTANCE.createEDataType();
			eDataType.setName(requireString(arguments, "name"));
			eDataType.setSerializable(optionalBoolean(arguments, "serializable", true));
			ePackage.getEClassifiers().add(eDataType);
			String objectId = EcoreAuthoring.put(dataset, eDataType, registry.limits());
			return Map.of("objectId", objectId, "name", eDataType.getName(), "objectCount", dataset.objectCount());
		});
	}
}
