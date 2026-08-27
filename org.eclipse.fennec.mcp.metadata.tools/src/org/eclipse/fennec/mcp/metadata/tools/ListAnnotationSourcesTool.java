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
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.metadata.tools.core.AnnotationScanner;
import org.eclipse.fennec.mcp.metadata.tools.core.MetadataViews;
import org.eclipse.fennec.mcp.metadata.tools.core.ToolException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool listing the EAnnotation vocabularies present across registered
 * packages: which sources exist, which detail keys each uses, and where.
 * <p>
 * This is the cold-start entry point. Every other annotation query needs an
 * exact source URI, and a wrong one matches nothing without saying so - the
 * quietest possible failure. Answering "what annotation sources exist here"
 * removes the guess.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "ListAnnotationSourcesTool", service = MCPTool.class, property = "tool.name=list_annotation_sources")
public class ListAnnotationSourcesTool extends AbstractMetadataTool {

	@Reference
	MetadataService metadata;

	@Activate
	void activate() {
		this.name = "list_annotation_sources";
		this.description = "List the EAnnotation source URIs present across registered packages, each with the "
				+ "detail keys it uses, how many elements carry it, and which namespaces it appears in. START "
				+ "HERE when you do not know a runtime's annotation vocabulary: find_classes_by_annotation and "
				+ "its siblings need an exact source URI, and a wrong one matches nothing without any error. "
				+ "Omit 'nsURI' to scan every registered package.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"nsURI": {
							"type": "string",
							"description": "Optional. Restrict the scan to one package's namespace URI. Omit to scan every registered package."
						}
					}
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String nsURI = optionalString(arguments, "nsURI");
			List<PackageMetadata> packages = MetadataViews.packages(metadata);
			if (nsURI != null) {
				packages = packages.stream().filter(p -> nsURI.equals(p.getNsURI())).toList();
				if (packages.isEmpty()) {
					throw new ToolException(String.format(
							"No package is registered under namespace '%s'. Call describe_metadata_status "
									+ "to see which namespaces are known to this runtime.", nsURI));
				}
			}

			List<Map<String, Object>> sources = AnnotationScanner.scan(packages);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("scannedNsURI", nsURI);
			result.put("scannedPackageVersions", packages.size());
			result.put("count", sources.size());
			result.put("annotationSources", sources);
			return result;
		});
	}
}
