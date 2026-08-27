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

import org.eclipse.fennec.emf.osgi.metadata.MetadataIndexReader;
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
 * MCP tool resolving a bare EClass name to its full {@code <nsURI>#//<Name>}
 * reference, without the caller having to know which package declares it.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "FindClassByNameTool", service = MCPTool.class, property = "tool.name=find_class_by_name")
public class FindClassByNameTool extends AbstractMetadataTool {

	@Reference
	MetadataService metadata;

	@Activate
	void activate() {
		this.name = "find_class_by_name";
		this.description = "Resolve an EClass name to its full <nsURI>#//<Name> reference without knowing which "
				+ "package declares it - the cross-package lookup that list_metamodel and describe_eclass cannot "
				+ "do, since both need the nsURI up front. Pass only 'className' to search every registered "
				+ "package; several packages may declare the same name, so more than one match is a normal "
				+ "answer. Pass 'nsURI' as well to pin the search to one package, which returns the newest "
				+ "registered version of that class.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"className": {
							"type": "string",
							"description": "The EClass name, e.g. 'UplinkMessage'. Not a reference - just the name."
						},
						"nsURI": {
							"type": "string",
							"description": "Optional. Restrict the search to this package's namespace URI."
						}
					},
					"required": ["className"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String className = requireString(arguments, "className");
			String nsURI = optionalString(arguments, "nsURI");

			MetadataIndexReader index = MetadataViews.requireIndex(metadata);
			List<ClassMetadata> found = nsURI == null
					? index.findAllByClassName(className)
					: index.findByClassName(nsURI, className).map(List::of).orElseGet(List::of);
			List<Map<String, Object>> classes = MetadataViews.hits(found, MetadataViews::classHit);

			Map<String, Object> query = new LinkedHashMap<>();
			query.put("className", className);
			query.put("nsURI", nsURI);
			query.put("searchedAllPackages", nsURI == null);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("query", query);
			result.put("count", classes.size());
			result.put("classes", classes);
			return result;
		});
	}
}
