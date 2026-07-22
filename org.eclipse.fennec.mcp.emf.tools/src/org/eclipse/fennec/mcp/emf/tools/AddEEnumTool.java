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

import org.eclipse.emf.ecore.EEnum;
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
 * MCP authoring tool adding an {@link EEnum} to a package. Add its literals
 * with add_eenum_literal.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEEnumTool", service = MCPTool.class, property = "tool.name=add_eenum")
public class AddEEnumTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "add_eenum";
		this.description = "Add an EEnum to a package. Returns its objectId; add literals with add_eenum_literal.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"packageObjectId": { "type": "string" },
						"name": { "type": "string" }
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
			EEnum eEnum = EcoreFactory.eINSTANCE.createEEnum();
			eEnum.setName(requireString(arguments, "name"));
			ePackage.getEClassifiers().add(eEnum);
			String objectId = EcoreAuthoring.put(dataset, eEnum, registry.limits());
			return Map.of("objectId", objectId, "name", eEnum.getName(), "objectCount", dataset.objectCount());
		});
	}
}
