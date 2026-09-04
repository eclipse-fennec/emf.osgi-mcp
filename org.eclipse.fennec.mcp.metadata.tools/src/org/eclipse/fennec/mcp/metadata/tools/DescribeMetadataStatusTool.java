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
import java.util.TreeSet;

import org.eclipse.fennec.emf.osgi.metadata.MetadataService;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.metadata.tools.core.AspectRenderer;
import org.eclipse.fennec.mcp.metadata.tools.core.MetadataViews;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool reporting how the metadata layer is wired in this runtime.
 * <p>
 * The index is bound optionally and dynamically, so it can legitimately be
 * absent - and a lookup against an absent index answers exactly like a lookup
 * that matched nothing. This tool is what makes the two distinguishable without
 * the agent spending turns on it. Unlike every other tool here it never requires
 * the index, so it still answers when nothing else can.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "DescribeMetadataStatusTool", service = MCPTool.class, property = "tool.name=describe_metadata_status")
public class DescribeMetadataStatusTool extends AbstractMetadataTool {

	@Reference
	MetadataService metadata;

	@Reference
	AnnotationVisibility visibility;

	@Activate
	void activate() {
		this.name = "describe_metadata_status";
		this.description = "Report how the metadata layer is wired here: whether a metadata index is bound, how "
				+ "many package versions are registered, which namespaces are known, where they came from "
				+ "(OSGi service or MCP session) and which aspect type ids are present. CALL THIS WHEN A LOOKUP "
				+ "COMES BACK EMPTY: without an index bound, every query answers empty for a reason that has "
				+ "nothing to do with what you asked. This tool works even then.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {}
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			boolean indexAvailable = metadata != null && metadata.getIndexReader().isPresent();
			List<PackageMetadata> packages = MetadataViews.packages(metadata);

			TreeSet<String> namespaces = new TreeSet<>();
			int osgi = 0;
			int session = 0;
			for (PackageMetadata packageMetadata : packages) {
				if (packageMetadata.getNsURI() != null) {
					namespaces.add(packageMetadata.getNsURI());
				}
				if (MetadataViews.ORIGIN_OSGI.equals(MetadataViews.origin(packageMetadata))) {
					osgi++;
				} else {
					session++;
				}
			}

			Map<String, Object> origins = new LinkedHashMap<>();
			origins.put(MetadataViews.ORIGIN_OSGI, osgi);
			origins.put(MetadataViews.ORIGIN_SESSION, session);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("metadataServiceAvailable", metadata != null);
			result.put("indexAvailable", indexAvailable);
			result.put("registeredPackageVersions", packages.size());
			result.put("distinctNamespaces", namespaces.size());
			result.put("namespaces", List.copyOf(namespaces));
			result.put("packageVersionsByOrigin", origins);
			result.put("aspectTypeIds", List.copyOf(AspectRenderer.summarize(packages, visibility).keySet()));
			if (!indexAvailable) {
				result.put("note", "No metadata index is bound, so every lookup tool in this bundle will "
						+ "report an error rather than an empty result. Deploy the bundle providing the "
						+ "MetadataIndex component to enable them.");
			}
			return result;
		});
	}
}
