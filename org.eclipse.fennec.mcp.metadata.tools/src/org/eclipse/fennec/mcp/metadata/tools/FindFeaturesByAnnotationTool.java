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
import org.eclipse.fennec.emf.osgi.model.metadata.FeatureMetadata;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.metadata.tools.core.MetadataViews;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool finding every registered structural feature carrying a given
 * EAnnotation detail, across all packages known to the metadata layer.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "FindFeaturesByAnnotationTool", service = MCPTool.class, property = "tool.name=find_features_by_annotation")
public class FindFeaturesByAnnotationTool extends AbstractMetadataTool {

	@Reference
	MetadataService metadata;

	@Activate
	void activate() {
		this.name = "find_features_by_annotation";
		this.description = "Find every EAttribute or EReference registered in this runtime that carries an "
				+ "EAnnotation detail, searching across all packages at once. OMIT 'value' TO MATCH ANY VALUE "
				+ "FOR THE KEY. Useful to answer 'which models already map this wire key' - e.g. which features "
				+ "carry an ExtendedMetaData name or a codec key. Returns feature identities "
				+ "(<nsURI>#//<Class>/<feature>, kind, type, multiplicity), not model content.";
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

			List<FeatureMetadata> found = MetadataViews.requireIndex(metadata)
					.findFeaturesByAnnotation(source, key, value);
			List<Map<String, Object>> features = MetadataViews.hits(found, featureMetadata -> {
				Map<String, Object> hit = MetadataViews.featureHit(featureMetadata);
				hit.put("matched", MetadataViews.matchedAnnotation(featureMetadata.getEFeature(), source, key));
				return hit;
			});

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("query", FindClassesByAnnotationTool.query(source, key, value));
			result.put("count", features.size());
			result.put("features", features);
			return result;
		});
	}
}
