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
import java.util.Objects;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.fennec.emf.osgi.metadata.MetadataService;
import org.eclipse.fennec.emf.osgi.model.metadata.AspectEntry;
import org.eclipse.fennec.emf.osgi.model.metadata.ClassMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.metadata.tools.core.MetadataViews;
import org.eclipse.fennec.mcp.metadata.tools.core.PackageSelector;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool describing one registered model version: its classes as references,
 * its registration origin and properties, and every registered version of its
 * namespace with their fingerprints.
 * <p>
 * Registration is keyed by model version, not by namespace, so one nsURI can
 * hold several concurrently registered versions. This is the tool that tells
 * them apart, and the {@code versions} list it always returns is how a caller
 * refused for an ambiguous nsURI learns which fingerprints exist - reachable
 * here by naming any one of them, and by {@code describe_metadata_status}
 * without naming anything.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "DescribePackageMetadataTool", service = MCPTool.class, property = "tool.name=describe_package_metadata")
public class DescribePackageMetadataTool extends AbstractMetadataTool {

	@Reference
	MetadataService metadata;

	@Activate
	void activate() {
		this.name = "describe_package_metadata";
		this.description = "Describe one registered model version: its class references, its model fingerprint, "
				+ "whether it came from an OSGi service or from an MCP session, its registration properties, the "
				+ "aspect type ids it carries, and every registered version of its namespace. Address it by "
				+ "'nsURI' or, exactly, by 'fingerprint'. Registration is keyed by model version rather than by "
				+ "namespace, so a namespace can legitimately hold several versions at once (the same model at "
				+ "two Atlas stages, say): where it does, an nsURI alone is REFUSED rather than answered with "
				+ "the newest, and the error lists the fingerprints to choose from.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"nsURI": {
							"type": "string",
							"description": "The package namespace URI, e.g. 'https://eclipse.org/fennec/lorawan'. Refused when the namespace holds more than one registered version - pass 'fingerprint' instead."
						},
						"fingerprint": {
							"type": "string",
							"description": "The model fingerprint, e.g. 'fp1:14466a0b5de879a6'. Addresses one version exactly, which is the only way to pick between several versions of one namespace. Use describe_metadata_status to list them."
						}
					}
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			PackageMetadata addressed = PackageSelector.require(metadata,
					optionalString(arguments, "nsURI"), optionalString(arguments, "fingerprint"));
			List<PackageMetadata> versions = metadata.getPackageMetadataVersions(addressed.getNsURI());

			Map<String, Object> result = new LinkedHashMap<>(describe(addressed));
			result.put("versionCount", versions.size());
			result.put("versions", versions.stream().map(DescribePackageMetadataTool::version).toList());
			return result;
		});
	}

	private static Map<String, Object> describe(PackageMetadata packageMetadata) {
		EPackage ePackage = packageMetadata.getEPackage();
		Map<String, Object> described = new LinkedHashMap<>(version(packageMetadata));
		described.put("name", ePackage == null ? null : ePackage.getName());
		described.put("nsPrefix", ePackage == null ? null : ePackage.getNsPrefix());

		List<String> classes = new ArrayList<>();
		List<String> abstractClasses = new ArrayList<>();
		for (ClassMetadata classMetadata : packageMetadata.getClasses()) {
			String reference = MetadataViews.classReference(classMetadata);
			classes.add(reference);
			if (classMetadata.getEClass() != null && classMetadata.getEClass().isAbstract()) {
				abstractClasses.add(reference);
			}
		}
		classes.sort(null);
		abstractClasses.sort(null);
		described.put("classCount", classes.size());
		described.put("classes", classes);
		described.put("abstractClasses", abstractClasses);
		described.put("properties", Map.copyOf(packageMetadata.getProperties().map()));
		return described;
	}

	private static Map<String, Object> version(PackageMetadata packageMetadata) {
		Map<String, Object> rendered = new LinkedHashMap<>();
		rendered.put("nsURI", packageMetadata.getNsURI());
		rendered.put("modelFingerprint", packageMetadata.getModelFingerprint());
		rendered.put("origin", MetadataViews.origin(packageMetadata));
		rendered.put("aspectTypeIds", packageMetadata.getAspects().stream()
				.map(AspectEntry::getTypeId).filter(Objects::nonNull).distinct().sorted().toList());
		return rendered;
	}
}
