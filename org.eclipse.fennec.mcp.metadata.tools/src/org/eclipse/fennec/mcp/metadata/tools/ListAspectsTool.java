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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.fennec.emf.osgi.metadata.MetadataService;
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
 * MCP tool listing the aspect type ids present across registered metadata.
 * <p>
 * Aspects are the generic extension point of the metadata layer: an
 * {@code AspectEntry} is a type id plus an arbitrary EMF payload, contributed by
 * whichever {@code MetadataHandler} is deployed. This tool therefore reports
 * whatever is there rather than any fixed set - today typically just
 * {@code codec}, more as providers are added, with no change here.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "ListAspectsTool", service = MCPTool.class, property = "tool.name=list_aspects")
public class ListAspectsTool extends AbstractMetadataTool {

	@Reference
	MetadataService metadata;

	@Activate
	void activate() {
		this.name = "list_aspects";
		this.description = "List the aspect type ids present across all registered metadata, with how many "
				+ "entries carry each and on which kinds of element (package, class, feature, operation). "
				+ "An aspect is parsed, structured metadata contributed by a provider - e.g. the 'codec' "
				+ "aspect holds a class's parsed serialization configuration. Use this to learn which aspect "
				+ "type ids exist here, then read one with describe_aspects.";
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
			Map<String, Map<String, Integer>> summary = AspectRenderer.summarize(MetadataViews.packages(metadata));

			List<Map<String, Object>> aspects = new ArrayList<>(summary.size());
			for (Map.Entry<String, Map<String, Integer>> entry : summary.entrySet()) {
				Map<String, Object> rendered = new LinkedHashMap<>();
				rendered.put("aspectTypeId", entry.getKey());
				rendered.put("entries", entry.getValue().values().stream().mapToInt(Integer::intValue).sum());
				rendered.put("elementKinds", entry.getValue());
				aspects.add(rendered);
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("count", aspects.size());
			result.put("aspects", aspects);
			return result;
		});
	}
}
