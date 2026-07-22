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
 * MCP tool importing an inline {@code .ecore} (XMI) into a new, editable dataset
 * and registering its packages so their classes become instantiable. The
 * loader is hardened (no href dereferencing, no DOCTYPE, size-capped,
 * unresolved references rejected); each package must be dynamic, valid and
 * allow-listed for registration.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "ImportEcoreTool", service = MCPTool.class, property = "tool.name=import_ecore")
public class ImportEcoreTool extends AbstractEMFTool {

	@Reference
	DatasetRegistry registry;
	@Reference
	PackageRegistry packages;

	@Activate
	void activate() {
		this.name = "import_ecore";
		this.description = "Import an inline .ecore (XMI) into a new editable dataset and register its packages "
				+ "so they become instantiable. External references are never dereferenced; the document must be "
				+ "self-contained (referencing only Ecore built-ins). Each package must be dynamic, valid and "
				+ "allow-listed for registration.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"xmi": { "type": "string", "description": "the inline .ecore document text" }
					},
					"required": ["xmi"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			String xmi = requireString(arguments, "xmi");
			List<EObject> roots = XmiImport.loadDetached(xmi, registry.limits().maxJsonPayloadBytes(), List.of());

			// pre-validate everything before mutating any state
			List<EPackage> ePackages = new ArrayList<>(roots.size());
			for (EObject root : roots) {
				if (!(root instanceof EPackage ePackage)) {
					throw new ToolException(String.format("import_ecore expects EPackage roots, but found a %s. Use import_instances for model instances.", root.eClass().getName()));
				}
				EcoreAuthoring.requireDynamic(ePackage);
				EcoreAuthoring.requireValid(ePackage);
				if (!packages.isRegistrable(ePackage.getNsURI())) {
					throw new ToolException(String.format("Namespace '%s' is not allow-listed for registration; an admin must add it to the EMFPackageRegistry nsuri.allowlist.", ePackage.getNsURI()));
				}
				ePackages.add(ePackage);
			}

			Dataset dataset = registry.create(sessionId, null);
			List<Map<String, Object>> registered = new ArrayList<>(ePackages.size());
			for (EPackage ePackage : ePackages) {
				String objectId = EcoreAuthoring.indexTree(dataset, ePackage, registry.limits());
				packages.register(sessionId, ePackage);
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("objectId", objectId);
				entry.put("nsURI", ePackage.getNsURI());
				registered.add(entry);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("datasetId", dataset.getId());
			result.put("packages", registered);
			result.put("objectCount", dataset.objectCount());
			result.put("hint", "The dataset is editable (add_*/modify_feature); the packages are instantiable via create_instance");
			return result;
		});
	}
}
