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

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool creating a new, empty dataset in the current MCP session.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "CreateDatasetTool", service = MCPTool.class, property = "tool.name=create_dataset")
public class CreateDatasetTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "create_dataset";
		this.description = "Create a new, empty dataset in this session. A dataset groups EMF object " +
				"instances built with create_instance/modify_feature, records a replayable build recipe " +
				"and can be validated and exported as XMI. Returns the datasetId for all further calls.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"seed": {
							"type": "integer",
							"description": "Optional reproducibility seed stored with the dataset (used by randomized fill operations)"
						}
					}
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			Dataset dataset = registry.create(sessionId(exchange), optionalLong(arguments, "seed"));
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("datasetId", dataset.getId());
			if (dataset.getSeed() != null) {
				result.put("seed", dataset.getSeed());
			}
			result.put("hint", "Use create_instance to add objects, modify_feature to populate them, export_dataset for XMI");
			return result;
		});
	}
}
