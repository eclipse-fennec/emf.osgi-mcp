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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.Exports;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.PackageRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * MCP tool serializing a registered EPackage to a standalone {@code .ecore}
 * document.
 * <p>
 * This used to be the only way to read a metamodel faithfully, because
 * {@code describe_eclass} emitted no EAnnotations, refused abstract classes and
 * reported supertypes as bare names. It now carries all three, so the reason to
 * come here is the <i>document</i> — a whole package in the form other EMF
 * tooling reads and writes — and no longer the fidelity. An agent that wants the
 * conventions of a class or two should describe them instead of paying for the
 * whole package.
 * <p>
 * Symmetric with {@code import_ecore}: inline XMI out, inline XMI in. That
 * symmetry is why the document stays all-or-nothing and unfilterable, and hence
 * why an OSGi package needs every one of its EClasses allow-listed.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
@Component(name = "ExportPackageTool", service = MCPTool.class, property = "tool.name=export_package")
public class ExportPackageTool extends AbstractEMFTool {

	private static final String FORMAT = "xmi";

	/** Keeps a namespace URI usable as a file name, without path separators. */
	private static final int MAX_FILE_NAME_LENGTH = 120;

	@Reference
	ModelGuard guard;
	@Reference
	AnnotationVisibility visibility;
	@Reference
	PackageRegistry packages;
	@Reference
	DatasetRegistry registry;

	@Activate
	void activate() {
		this.name = "export_package";
		this.description = "Serialize a registered EPackage to a standalone .ecore document: a whole metamodel in "
				+ "the form other EMF tooling reads and writes. Use it when you want the document — to hand a "
				+ "package on, or to emit the .ecore of one you registered in this session. To read the "
				+ "conventions of a class before copying it, use describe_eclass instead: it now reports "
				+ "EAnnotations in their exact spelling, abstract classes and qualified supertypes, and costs "
				+ "you one class rather than the whole package. Classes in other packages are written as external references "
				+ "'<nsURI>#//<Name>', never inlined — so a document with external references will not re-import "
				+ "into a fresh session, because import_ecore seeds no packages and refuses unresolved ones. "
				+ "Session-registered packages are exported as they are; an OSGi package must be allow-listed, "
				+ "and so must every one of its EClasses, since the document cannot be filtered.";
		this.inputSchema = """
				{
					"type": "object",
					"properties": {
						"nsURI": {
							"type": "string",
							"description": "The namespace URI of the package to export, e.g. 'http://example.org/library'. Use list_registry for this session's packages and list_metamodel for the allow-listed OSGi ones."
						},
						"format": {
							"type": "string",
							"enum": ["xmi"],
							"description": "The serialization format (default xmi). A .ecore document is XMI; the parameter exists for symmetry with export_dataset."
						}
					},
					"required": ["nsURI"]
				}
				""";
	}

	@Override
	public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
		return run(() -> {
			String sessionId = sessionId(exchange);
			String nsURI = requireString(arguments, "nsURI");
			String format = optionalString(arguments, "format");
			if (format != null && !FORMAT.equals(format)) {
				throw new ToolException(String.format("Unknown format '%s'. A .ecore document is XMI; use 'xmi'", format));
			}

			// Session-local first: a package this session authored or imported is
			// already policy-checked by the registration allow-list, and handing an
			// agent back its own model discloses nothing. Only then the OSGi
			// population, where the allow-list is the control.
			EPackage ePackage = packages.resolve(sessionId, nsURI);
			String origin = "session";
			if (ePackage == null) {
				ePackage = guard.requireAllowedPackage(nsURI);
				origin = "osgi";
				requireFullyAllowListed(ePackage);
				requireEveryAnnotationVisible(ePackage);
			}

			String content = Exports.toEcore(ePackage);
			int byteSize = content.getBytes(StandardCharsets.UTF_8).length;

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("nsURI", nsURI);
			result.put("name", ePackage.getName());
			result.put("origin", origin);
			result.put("format", FORMAT);
			result.put("classifierCount", ePackage.getEClassifiers().size());
			result.put("byteSize", byteSize);

			int cap = registry.limits().maxInlineExportBytes();
			if (byteSize <= cap) {
				result.put("content", content);
			} else {
				result.put("inline", false);
				Path file = registry.storeExport(sessionId, fileNameFor(nsURI), "ecore", content);
				result.put("file", file.toString());
				result.put("note", String.format(
						"Export of %d bytes exceeds the inline cap of %d bytes and was written to '%s'. "
								+ "Raise max.inline.export.bytes in the EMFDatasetRegistry configuration to inline more.",
						byteSize, cap, file));
			}
			return result;
		});
	}

	/**
	 * A {@code .ecore} document is all-or-nothing — unlike a structured describer
	 * it cannot omit a class — so exporting an OSGi package in full requires every
	 * one of its EClasses on the class allow-list.
	 * <p>
	 * The denied classes are counted, never named: naming them would disclose
	 * exactly what {@code list_metamodel} withholds.
	 */
	/**
	 * A {@code .ecore} carries every EAnnotation of the package, so a package with
	 * a denied annotation source cannot be exported at all — the same all-or-nothing
	 * rule as {@link #requireFullyAllowListed}, for the same reason: the document
	 * cannot be filtered without ceasing to be the package.
	 * <p>
	 * Stripping the denied annotations instead would produce a document that no
	 * longer says what it claims to say, and that a re-import would silently
	 * flatten. Refusing keeps {@code export_package} honest and leaves
	 * {@code describe_eclass} — which <em>can</em> filter, and reports a count of
	 * what it withheld — as the way to read such a package.
	 * <p>
	 * Only for packages from the OSGi registry. A session-registered package is
	 * the agent's own authored or imported work, so withholding it from the agent
	 * protects nothing, exactly as the allow-list is not re-checked for those.
	 * <p>
	 * The offending sources are counted, never named.
	 */
	private void requireEveryAnnotationVisible(EPackage ePackage) {
		if (visibility.isUnrestricted()) {
			return;
		}
		Set<String> denied = new LinkedHashSet<>();
		collectDeniedSources(ePackage, denied);
		for (Iterator<EObject> contents = ePackage.eAllContents(); contents.hasNext();) {
			collectDeniedSources(contents.next(), denied);
		}
		if (!denied.isEmpty()) {
			throw new ToolException(String.format(
					"EPackage '%s' cannot be exported: %d of its EAnnotation source(s) are withheld by the "
							+ "deployment. A .ecore document is the whole package and carries every annotation, so "
							+ "it cannot be filtered. Use describe_eclass to read the classes you need; it omits "
							+ "the withheld annotations and reports how many it omitted.",
					ePackage.getNsURI(), denied.size()));
		}
	}

	private void collectDeniedSources(EObject eObject, Set<String> denied) {
		if (!(eObject instanceof EModelElement element)) {
			return;
		}
		for (EAnnotation annotation : element.getEAnnotations()) {
			if (!visibility.isSourceVisible(annotation.getSource())) {
				denied.add(annotation.getSource());
			}
		}
	}

	private void requireFullyAllowListed(EPackage ePackage) {
		List<EClass> classes = ePackage.getEClassifiers().stream()
				.filter(EClass.class::isInstance)
				.map(EClass.class::cast)
				.toList();
		long denied = classes.stream().filter(eClass -> !guard.isClassAllowed(eClass)).count();
		if (denied > 0) {
			throw new ToolException(String.format(
					"EPackage '%s' cannot be exported: %d of its %d EClasses are not allow-listed. A .ecore "
							+ "document is the whole package and cannot be filtered, so export_package requires "
							+ "every EClass of the package on the EMFModelGuard eclass.allowlist.",
					ePackage.getNsURI(), denied, classes.size()));
		}
	}

	private static String fileNameFor(String nsURI) {
		String sanitized = nsURI.replaceAll("[^A-Za-z0-9._-]", "_");
		return sanitized.length() <= MAX_FILE_NAME_LENGTH ? sanitized : sanitized.substring(0, MAX_FILE_NAME_LENGTH);
	}
}
