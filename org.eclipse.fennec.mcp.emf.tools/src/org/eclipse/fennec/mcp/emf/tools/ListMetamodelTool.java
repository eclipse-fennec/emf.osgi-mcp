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
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool listing the allow-listed metamodel: without arguments it returns
 * the allow-listed EPackages; with an {@code nsURI} it returns the
 * allow-listed concrete EClasses of that package. Anything not allow-listed
 * is invisible (deny-all).
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "ListMetamodelTool", service = MCPTool.class, property = "tool.name=list_metamodel")
public class ListMetamodelTool extends AbstractEMFTool {

	@Reference
	ModelGuard guard;

	@Activate
	void activate() {
		this.name = "list_metamodel";
		this.description = "Discover the EMF metamodel available for instance creation. " +
				"Without arguments, lists the available EPackages. With 'nsURI' or 'fingerprint', lists the " +
				"instantiable EClasses of that package. Use describe_eclass for feature details.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"nsURI": {
							"type": "string",
							"description": "Optional namespace URI of an EPackage. If set, the instantiable EClasses of this package are returned."
						},
						"fingerprint": {
							"type": "string",
							"description": "Optional model fingerprint, e.g. 'fp1:14466a0b5de879a6', addressing one registered model version exactly. Needed when a namespace holds several registered versions, where an nsURI alone is refused rather than resolved to an arbitrary one. Use describe_package_metadata or describe_metadata_status (metadata tools) to find it."
						}
					}
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String nsUri = optionalString(arguments, "nsURI");
			String fingerprint = optionalString(arguments, "fingerprint");
			if (nsUri == null && fingerprint == null) {
				List<Map<String, Object>> packages = guard.allowedPackages().stream()
						.map(this::describePackage)
						.toList();
				return Map.of("ePackages", packages);
			}
			EPackage ePackage = guard.requireAllowedPackage(nsUri, fingerprint);
			List<Map<String, Object>> classes = guard.allowedConcreteClasses(ePackage).stream()
					.map(eClass -> {
						Map<String, Object> entry = new LinkedHashMap<String, Object>();
						entry.put("name", eClass.getName());
						entry.put("eClass", ModelGuard.refOf(eClass));
						return entry;
					})
					.toList();
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("nsURI", ePackage.getNsURI());
			// Which version this describes, so a result can be traced back to the model
			// version it came from rather than only to a namespace.
			result.put("modelFingerprint", guard.fingerprintOf(ePackage));
			result.put("eClasses", classes);
			return result;
		});
	}

	private Map<String, Object> describePackage(EPackage ePackage) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("name", ePackage.getName());
		entry.put("nsURI", ePackage.getNsURI());
		entry.put("modelFingerprint", guard.fingerprintOf(ePackage));
		entry.put("eClassCount", guard.allowedConcreteClasses(ePackage).size());
		return entry;
	}
}
