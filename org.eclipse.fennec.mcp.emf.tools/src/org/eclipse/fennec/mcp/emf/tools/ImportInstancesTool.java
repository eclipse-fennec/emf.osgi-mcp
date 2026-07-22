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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.EcoreAuthoring;
import org.eclipse.fennec.mcp.emf.tools.core.PackageRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;
import org.eclipse.fennec.mcp.emf.tools.core.XmiImport;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool importing an inline model-instance XMI into a new editable dataset.
 * Its metamodel package must already be registered in the session (via
 * import_ecore or authoring + register_package); the loader is hardened and
 * rejects references to anything but Ecore and that package.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "ImportInstancesTool", service = MCPTool.class, property = "tool.name=import_instances")
public class ImportInstancesTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	PackageRegistry packages;

	@Activate
	void activate() {
		this.name = "import_instances";
		this.description = "Import an inline model-instance XMI into a new editable dataset. The instances' "
				+ "metamodel package (nsURI) must already be registered in this session (import_ecore first, or "
				+ "author it and register_package). External references are never dereferenced.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"xmi": { "type": "string", "description": "the inline instance XMI document text" },
						"nsURI": { "type": "string", "description": "namespace URI of the instances' (already registered) metamodel package" }
					},
					"required": ["xmi", "nsURI"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			String xmi = requireString(arguments, "xmi");
			String nsUri = requireString(arguments, "nsURI");
			EPackage metamodel = packages.resolve(sessionId, nsUri);
			if (metamodel == null) {
				throw new ToolException(String.format("No registered package '%s' in this session. Import the .ecore (import_ecore) or author it and register_package first.", nsUri));
			}

			List<EObject> roots = XmiImport.loadDetached(xmi, registry.limits().maxJsonPayloadBytes(), List.of(metamodel));
			Dataset dataset = registry.create(sessionId, null);
			List<String> rootIds = new ArrayList<>(roots.size());
			for (EObject root : roots) {
				rootIds.add(EcoreAuthoring.indexTree(dataset, root, registry.limits()));
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("datasetId", dataset.getId());
			result.put("rootObjectIds", rootIds);
			result.put("objectCount", dataset.objectCount());
			result.put("hint", "The dataset is editable (modify_feature) and exportable (export_dataset)");
			return result;
		});
	}
}
