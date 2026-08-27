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
import org.eclipse.fennec.emf.osgi.model.metadata.ClassMetadata;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.metadata.tools.core.MetadataViews;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool finding every registered EClass carrying a given EAnnotation detail,
 * across all packages known to the metadata layer.
 * <p>
 * The one tool that reaches classes nothing else can: {@code list_metamodel}
 * filters to concrete classes and {@code describe_eclass} rejects abstract ones,
 * so an abstract family parent declaring a discriminator path is invisible to
 * both.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "FindClassesByAnnotationTool", service = MCPTool.class, property = "tool.name=find_classes_by_annotation")
public class FindClassesByAnnotationTool extends AbstractMetadataTool {

	@Reference
	MetadataService metadata;

	@Activate
	void activate() {
		this.name = "find_classes_by_annotation";
		this.description = "Find every EClass registered in this runtime that carries an EAnnotation detail, "
				+ "searching across all packages at once - both those deployed as OSGi services and those "
				+ "registered by this session. OMIT 'value' TO MATCH ANY VALUE FOR THE KEY: that wildcard is "
				+ "what locates a whole family, e.g. find_classes_by_annotation(source, 'typeDiscriminatorPath') "
				+ "with no value returns the abstract parent that declares the path - a class list_metamodel "
				+ "hides and describe_eclass refuses. Returns identities (<nsURI>#//<Name>, abstract flag, "
				+ "supertype references), not model content. Use list_annotation_sources first if you do not "
				+ "already know the exact annotation source URI - a wrong source silently matches nothing.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"annotationSource": {
							"type": "string",
							"description": "The EAnnotation source URI, e.g. 'http://eclipse.org/fennec/codec/typeMapping/lorawan'. Discover the available sources with list_annotation_sources."
						},
						"key": {
							"type": "string",
							"description": "The annotation detail key that must be present, e.g. 'typeDiscriminatorPath'"
						},
						"value": {
							"type": "string",
							"description": "Optional. The detail value to match exactly. OMIT IT to match any value for the key - that is how you find every class carrying the key at all."
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

			List<ClassMetadata> found = MetadataViews.requireIndex(metadata)
					.findClassesByAnnotation(source, key, value);
			List<Map<String, Object>> classes = MetadataViews.hits(found, classMetadata -> {
				Map<String, Object> hit = MetadataViews.classHit(classMetadata);
				hit.put("matched", MetadataViews.matchedAnnotation(classMetadata.getEClass(), source, key));
				return hit;
			});

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("query", query(source, key, value));
			result.put("count", classes.size());
			result.put("classes", classes);
			return result;
		});
	}

	static Map<String, Object> query(String source, String key, String value) {
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("annotationSource", source);
		query.put("key", key);
		query.put("value", value);
		query.put("matchedAnyValue", value == null);
		return query;
	}
}
