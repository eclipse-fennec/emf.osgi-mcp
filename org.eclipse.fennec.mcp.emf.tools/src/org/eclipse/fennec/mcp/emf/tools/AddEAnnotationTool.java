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

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EModelElement;
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
 * MCP authoring tool adding an {@link EAnnotation} to any EModelElement
 * (package, class, feature, ...). Details are opaque string key/value pairs;
 * references are objectIds of other model elements in the same dataset.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "AddEAnnotationTool", service = MCPTool.class, property = "tool.name=add_eannotation")
public class AddEAnnotationTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "add_eannotation";
		this.description = "Add an EAnnotation to any model element (targetObjectId). 'source' is the annotation "
				+ "source URI; 'details' is an object of string key/value pairs; 'references' are objectIds of "
				+ "other model elements in the dataset.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"targetObjectId": { "type": "string", "description": "objectId of the annotated model element" },
						"source": { "type": "string" },
						"details": { "type": "object", "description": "string key/value pairs" },
						"references": { "type": "array", "items": { "type": "string" }, "description": "objectIds of referenced model elements" }
					},
					"required": ["datasetId", "targetObjectId", "source"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Dataset dataset = registry.require(sessionId(exchange), requireString(arguments, "datasetId"));
			EModelElement target = EcoreAuthoring.requireEModelElement(dataset, requireString(arguments, "targetObjectId"));
			EAnnotation annotation = NestedAuthoring.buildEAnnotation(dataset, arguments);
			target.getEAnnotations().add(annotation);
			String objectId = EcoreAuthoring.put(dataset, annotation, registry.limits());
			return Map.of("objectId", objectId, "source", annotation.getSource(), "objectCount", dataset.objectCount());
		});
	}
}
