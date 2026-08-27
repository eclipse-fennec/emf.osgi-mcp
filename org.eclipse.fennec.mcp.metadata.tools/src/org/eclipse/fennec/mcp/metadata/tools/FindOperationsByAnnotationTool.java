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
package org.eclipse.fennec.mcp.metadata.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.fennec.emf.osgi.metadata.MetadataService;
import org.eclipse.fennec.emf.osgi.model.metadata.OperationMetadata;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.metadata.tools.core.MetadataViews;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool finding every registered EOperation carrying a given EAnnotation
 * detail, across all packages known to the metadata layer.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "FindOperationsByAnnotationTool", service = MCPTool.class, property = "tool.name=find_operations_by_annotation")
public class FindOperationsByAnnotationTool extends AbstractMetadataTool {

	@Reference
	MetadataService metadata;

	@Activate
	void activate() {
		this.name = "find_operations_by_annotation";
		this.description = "Find every EOperation registered in this runtime that carries an EAnnotation detail, "
				+ "searching across all packages at once. OMIT 'value' TO MATCH ANY VALUE FOR THE KEY. Returns "
				+ "operation identities (<nsURI>#//<Class>/<operation>, return type, parameters), not model content.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"annotationSource": {
							"type": "string",
							"description": "The EAnnotation source URI. Discover the available sources with list_annotation_sources."
						},
						"key": {
							"type": "string",
							"description": "The annotation detail key that must be present"
						},
						"value": {
							"type": "string",
							"description": "Optional. The detail value to match exactly. OMIT IT to match any value for the key."
						}
					},
					"required": ["annotationSource", "key"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String source = requireString(arguments, "annotationSource");
			String key = requireString(arguments, "key");
			String value = optionalString(arguments, "value");

			List<OperationMetadata> found = MetadataViews.requireIndex(metadata)
					.findOperationsByAnnotation(source, key, value);
			List<Map<String, Object>> operations = MetadataViews.hits(found, operationMetadata -> {
				Map<String, Object> hit = MetadataViews.operationHit(operationMetadata);
				hit.put("matched", MetadataViews.matchedAnnotation(operationMetadata.getEOperation(), source, key));
				return hit;
			});

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("query", FindClassesByAnnotationTool.query(source, key, value));
			result.put("count", operations.size());
			result.put("operations", operations);
			return result;
		});
	}
}
