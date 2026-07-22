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

import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.util.Diagnostician;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.EcoreAuthoring;
import org.eclipse.fennec.mcp.emf.tools.core.PackageRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool validating an authored {@link EPackage} and registering a frozen
 * copy of it into the session-local package registry, making its classes
 * instantiable via create_instance. Registration is refused for a package with
 * validation errors or a non-dynamic classifier (instanceClassName). If the
 * package's nsURI changed, pass 'previousNsURI' to drop the stale registration.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "RegisterPackageTool", service = MCPTool.class, property = "tool.name=register_package")
public class RegisterPackageTool extends AbstractEMFTool {

	private static final int MAX_ERRORS = 10;

	@Reference
	DatasetRegistry registry;
	@Reference
	PackageRegistry packages;

	@Activate
	void activate() {
		this.name = "register_package";
		this.description = "Validate an authored EPackage and register a frozen copy into the session so its "
				+ "classes become instantiable with create_instance. Refused on validation errors or a "
				+ "non-dynamic classifier. If the nsURI changed, pass 'previousNsURI' to remove the old entry.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"datasetId": { "type": "string" },
						"packageObjectId": { "type": "string", "description": "objectId of the EPackage to register" },
						"previousNsURI": { "type": "string", "description": "optional; a previous nsURI of this package to unregister" }
					},
					"required": ["datasetId", "packageObjectId"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			Dataset dataset = registry.require(sessionId, requireString(arguments, "datasetId"));
			EPackage ePackage = EcoreAuthoring.requireEPackage(dataset, requireString(arguments, "packageObjectId"));

			EcoreAuthoring.requireDynamic(ePackage);
			Diagnostic diagnostic = Diagnostician.INSTANCE.validate(ePackage);
			if (diagnostic.getSeverity() >= Diagnostic.ERROR) {
				throw new ToolException(String.format("Cannot register '%s': the metamodel has validation errors: %s",
						ePackage.getNsURI(), collectErrors(diagnostic)));
			}

			String previousNsURI = optionalString(arguments, "previousNsURI");
			if (previousNsURI != null && !previousNsURI.equals(ePackage.getNsURI())) {
				packages.unregister(sessionId, previousNsURI);
			}
			EPackage registered = packages.register(sessionId, ePackage);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("nsURI", registered.getNsURI());
			result.put("registeredClasses", registered.getEClassifiers().stream().filter(EClass.class::isInstance).count());
			result.put("valid", Boolean.TRUE);
			result.put("hint", "The package is now instantiable: use create_instance with <nsURI>#//<ClassName>");
			return result;
		});
	}

	private static String collectErrors(Diagnostic diagnostic) {
		List<String> messages = new ArrayList<>();
		for (Diagnostic child : diagnostic.getChildren()) {
			if (child.getSeverity() >= Diagnostic.ERROR && messages.size() < MAX_ERRORS) {
				messages.add(child.getMessage());
			}
		}
		return messages.isEmpty() ? diagnostic.getMessage() : String.join("; ", messages);
	}
}
