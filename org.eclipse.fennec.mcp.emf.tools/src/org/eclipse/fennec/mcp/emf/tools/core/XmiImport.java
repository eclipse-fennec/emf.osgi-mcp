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
package org.eclipse.fennec.mcp.emf.tools.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ExtensibleURIConverterImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;

/**
 * Hardened loader for agent-supplied inline XMI. It mirrors {@link ModelGuard}'s
 * registry-only principle:
 * <ul>
 * <li>the document is parsed from the given text only — a URIConverter that
 * refuses every {@code createInputStream} makes href dereferencing impossible
 * (no SSRF, no file reads);</li>
 * <li>DOCTYPE / external / general entities are disallowed (XXE, billion-laughs);</li>
 * <li>the input is size-capped;</li>
 * <li>after loading, any unresolved proxy is rejected — the document may only
 * reference the packages made available (the built-in Ecore package plus the
 * explicitly supplied session packages).</li>
 * </ul>
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
public final class XmiImport {

	private XmiImport() {
	}

	/**
	 * Parses inline XMI into detached root objects.
	 *
	 * @param xmi               the inline document text
	 * @param maxBytes          the maximum accepted UTF-8 size
	 * @param availablePackages packages the document may reference (in addition
	 *                          to the built-in Ecore package); each is copied
	 *                          into the load resource set so its classifiers
	 *                          resolve without any I/O
	 * @return the detached root EObjects (no resource, no container)
	 * @throws ToolException on an oversized document, a DOCTYPE, a parse error or an unresolved reference
	 */
	public static List<EObject> loadDetached(String xmi, int maxBytes, List<EPackage> availablePackages) {
		if (xmi == null || xmi.isBlank()) {
			throw new ToolException("Parameter 'xmi' is required and must be the inline XMI document text");
		}
		byte[] bytes = xmi.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > maxBytes) {
			throw new ToolException(String.format("The XMI document exceeds the import limit of %d bytes", maxBytes));
		}
		if (containsDoctype(xmi)) {
			throw new ToolException("The XMI document declares a DOCTYPE/DTD, which is not allowed");
		}

		ResourceSet resourceSet = new ResourceSetImpl();
		// registry-only: never dereference any href/URI to an external resource
		resourceSet.setURIConverter(new ExtensibleURIConverterImpl() {
			@Override
			public InputStream createInputStream(URI uri, Map<?, ?> options) throws IOException {
				throw new IOException("External resource loading is disabled for imported documents: " + uri);
			}
		});
		for (EPackage available : availablePackages) {
			EPackage copy = EcoreUtil.copy(available);
			Resource packageResource = new XMIResourceImpl(URI.createURI(available.getNsURI()));
			packageResource.getContents().add(copy);
			resourceSet.getResources().add(packageResource);
			resourceSet.getPackageRegistry().put(available.getNsURI(), copy);
		}

		XMIResourceImpl resource = new XMIResourceImpl(URI.createURI("fennec-mcp://import/document.xmi"));
		resourceSet.getResources().add(resource);
		try {
			resource.load(new ByteArrayInputStream(bytes), hardenedOptions());
		} catch (IOException | RuntimeException e) {
			throw new ToolException("Cannot parse the XMI document: " + e.getMessage());
		}
		if (resource.getContents().isEmpty()) {
			throw new ToolException("The XMI document contains no objects");
		}

		Map<EObject, Collection<EStructuralFeature.Setting>> unresolved = EcoreUtil.UnresolvedProxyCrossReferencer.find(resource);
		if (!unresolved.isEmpty()) {
			throw new ToolException(String.format(
					"The document references types that are not available in this session: %s. For instances, import the .ecore and register the package first.",
					describeProxies(unresolved.keySet())));
		}

		List<EObject> roots = new ArrayList<>(resource.getContents());
		resource.getContents().clear(); // detach from the transient load resource
		return roots;
	}

	private static Map<Object, Object> hardenedOptions() {
		Map<String, Boolean> features = new HashMap<>();
		features.put("http://apache.org/xml/features/disallow-doctype-decl", Boolean.TRUE);
		features.put("http://xml.org/sax/features/external-general-entities", Boolean.FALSE);
		features.put("http://xml.org/sax/features/external-parameter-entities", Boolean.FALSE);
		Map<Object, Object> options = new HashMap<>();
		options.put(XMLResource.OPTION_PARSER_FEATURES, features);
		return options;
	}

	private static boolean containsDoctype(String xmi) {
		return xmi.toUpperCase(java.util.Locale.ROOT).contains("<!DOCTYPE");
	}

	private static String describeProxies(Collection<EObject> proxies) {
		List<String> uris = new ArrayList<>();
		for (EObject proxy : proxies) {
			URI uri = EcoreUtil.getURI(proxy);
			if (uri != null && !uris.contains(uri.toString())) {
				uris.add(uri.toString());
			}
			if (uris.size() >= 5) {
				break;
			}
		}
		return String.join(", ", uris);
	}
}
