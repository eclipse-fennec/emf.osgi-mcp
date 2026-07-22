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

import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.PackageRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool removing a package from the session-local registry by namespace URI.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "UnregisterPackageTool", service = MCPTool.class, property = "tool.name=unregister_package")
public class UnregisterPackageTool extends AbstractEMFTool {

	@Reference
	PackageRegistry packages;

	@Activate
	void activate() {
		this.name = "unregister_package";
		this.description = "Remove a package from the session-local registry by nsURI. Existing instances of it "
				+ "in datasets stay live but the class becomes non-instantiable again.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"nsURI": { "type": "string" }
					},
					"required": ["nsURI"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String nsUri = requireString(arguments, "nsURI");
			boolean removed = packages.unregister(sessionId(exchange), nsUri);
			return Map.of("nsURI", nsUri, "removed", removed);
		});
	}
}
