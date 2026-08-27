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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.EcoreResourceFactoryImpl;
import org.eclipse.emf.ecore.xmi.impl.URIHandlerImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;

/**
 * XMI serialization of a dataset. All root objects are placed into one XMI
 * resource; the output is deterministic for an identical object graph, which
 * is the basis of the recipe-replay reproducibility guarantee.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public final class Exports {

	/** URI scheme/prefix under which dataset exports are addressed. */
	public static final String EXPORT_URI_PREFIX = "fennec-mcp://datasets/";

	private static final EcoreResourceFactoryImpl ECORE_RESOURCE_FACTORY = new EcoreResourceFactoryImpl();

	private Exports() {
	}

	/**
	 * Serializes all roots of the dataset to one XMI document.
	 *
	 * @param dataset the dataset to serialize
	 * @return the XMI content as UTF-8 string
	 * @throws ToolException if the dataset is empty or serialization fails
	 */
	public static String toXmi(Dataset dataset) {
		if (dataset.roots().isEmpty()) {
			throw new ToolException(String.format("Dataset '%s' has no root objects to serialize", dataset.getId()));
		}
		URI uri = URI.createURI(EXPORT_URI_PREFIX + dataset.getId() + ".xmi");
		// drop a previous export resource of this dataset, if any
		dataset.getResourceSet().getResources().removeIf(r -> uri.equals(r.getURI()));
		XMIResourceImpl resource = new XMIResourceImpl(uri);
		dataset.getResourceSet().getResources().add(resource);
		resource.getContents().addAll(dataset.roots());
		Map<String, Object> options = new LinkedHashMap<>();
		options.put(XMLResource.OPTION_ENCODING, StandardCharsets.UTF_8.name());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			resource.save(out, options);
		} catch (IOException | RuntimeException e) {
			throw new ToolException("XMI serialization failed: " + e.getMessage());
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	/**
	 * Serializes one {@link EPackage} to a standalone {@code .ecore} document.
	 * <p>
	 * The package and every foreign package it references are copied in a single
	 * {@link EcoreUtil.Copier} pass, so {@code copyReferences()} re-points the
	 * copy's cross-references at the copied foreign objects; each copy then goes
	 * into a resource keyed by its own namespace URI. That is what turns an
	 * external supertype into {@code <nsURI>#//<Name>}. Without it the reference
	 * is either dangling — a frozen package from {@link PackageRegistry} has no
	 * resource at all — or a file path, if the package happened to be loaded from
	 * one.
	 * <p>
	 * The referenced packages are <b>not</b> inlined: only the requested package
	 * is serialized, and the reference stays a reference.
	 *
	 * @param ePackage the package to serialize
	 * @return the {@code .ecore} content as UTF-8 string
	 * @throws ToolException if the package has no namespace URI or serialization fails
	 */
	public static String toEcore(EPackage ePackage) {
		String nsURI = ePackage.getNsURI();
		if (nsURI == null || nsURI.isBlank()) {
			throw new ToolException("The package has no namespace URI and cannot be serialized");
		}
		EcoreUtil.Copier copier = new EcoreUtil.Copier();
		EPackage copy = (EPackage) copier.copy(ePackage);
		Map<String, EPackage> foreignCopies = new LinkedHashMap<>();
		for (EPackage foreign : foreignPackages(ePackage)) {
			foreignCopies.put(foreign.getNsURI(), (EPackage) copier.copy(foreign));
		}
		copier.copyReferences();

		ResourceSet resourceSet = new ResourceSetImpl();
		Resource resource = packageResource(resourceSet, nsURI, copy);
		foreignCopies.forEach((foreignNsURI, foreignCopy) -> packageResource(resourceSet, foreignNsURI, foreignCopy));

		URI documentURI = URI.createURI(nsURI);
		Map<String, Object> options = new LinkedHashMap<>();
		options.put(XMLResource.OPTION_ENCODING, StandardCharsets.UTF_8.name());
		// Deresolve references into this document to their '#//Name' fragment form,
		// and leave every other reference absolute. EMF's default handler deresolves
		// both, which turns a foreign package sharing a host into a bare relative
		// segment ('lorawan#//UplinkMessage') that no importer can resolve back to a
		// namespace; suppressing it wholesale instead flattens the document's own
		// references to '//Name', which is not the '.ecore' form other EMF tooling
		// writes.
		options.put(XMLResource.OPTION_URI_HANDLER, new URIHandlerImpl() {
			@Override
			public URI deresolve(URI uri) {
				return documentURI.equals(uri.trimFragment())
						? URI.createURI("#" + uri.fragment())
						: uri;
			}
		});
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			resource.save(out, options);
		} catch (IOException | RuntimeException e) {
			throw new ToolException(String.format(
					"Ecore serialization of '%s' failed: %s. This usually means the package references a "
							+ "classifier that belongs to no registered package.", nsURI, e.getMessage()));
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	/**
	 * The distinct root packages referenced from outside the given package.
	 * {@link EcorePackage} is left out: it already sits in a resource keyed by its
	 * own namespace URI, so its built-ins serialize correctly untouched.
	 */
	private static Collection<EPackage> foreignPackages(EPackage ePackage) {
		Map<String, EPackage> foreign = new LinkedHashMap<>();
		collectForeign(ePackage, ePackage, foreign);
		for (Iterator<EObject> contents = ePackage.eAllContents(); contents.hasNext();) {
			collectForeign(ePackage, contents.next(), foreign);
		}
		return foreign.values();
	}

	private static void collectForeign(EPackage root, EObject eObject, Map<String, EPackage> foreign) {
		for (EObject referenced : eObject.eCrossReferences()) {
			EPackage owner = rootPackageOf(referenced);
			if (owner == null || owner == root || owner == EcorePackage.eINSTANCE) {
				continue;
			}
			String nsURI = owner.getNsURI();
			if (nsURI != null && !nsURI.isBlank()) {
				foreign.putIfAbsent(nsURI, owner);
			}
		}
	}

	private static EPackage rootPackageOf(EObject eObject) {
		for (EObject current = eObject; current != null; current = current.eContainer()) {
			if (current instanceof EPackage candidate && candidate.getESuperPackage() == null) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Places a package in a resource keyed by its own namespace URI, which is what
	 * makes a reference into it serialize as {@code <nsURI>#//<Name>}.
	 * <p>
	 * The resource comes from {@link EcoreResourceFactoryImpl}, not a plain
	 * {@code XMIResourceImpl}: it is the factory every {@code .ecore} is written
	 * with, and it emits the qualified {@code eType="ecore:EClass <uri>"} form that
	 * other EMF tooling reads back.
	 */
	private static Resource packageResource(ResourceSet resourceSet, String nsURI, EPackage ePackage) {
		Resource resource = ECORE_RESOURCE_FACTORY.createResource(URI.createURI(nsURI));
		resourceSet.getResources().add(resource);
		resource.getContents().add(ePackage);
		resourceSet.getPackageRegistry().put(nsURI, ePackage);
		return resource;
	}

	/**
	 * @param dataset the dataset
	 * @param format  the export format extension ({@code xmi} or {@code json})
	 * @return the stable export resource URI of a dataset
	 */
	public static String exportUri(Dataset dataset, String format) {
		return EXPORT_URI_PREFIX + dataset.getId() + "." + format;
	}

	/**
	 * @param dataset the dataset
	 * @return object counts per EClass name, sorted by class name
	 */
	public static Map<String, Integer> eClassCounts(Dataset dataset) {
		Map<String, Integer> counts = new TreeMap<>();
		for (EObject eObject : dataset.objectsSnapshot().values()) {
			counts.merge(eObject.eClass().getName(), 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * Removes the export resource of the dataset, leaving the objects untouched.
	 * @param dataset the dataset
	 * @param format the export format extension
	 */
	public static void dropExportResource(Dataset dataset, String format) {
		URI uri = URI.createURI(EXPORT_URI_PREFIX + dataset.getId() + "." + format);
		for (Resource resource : dataset.getResourceSet().getResources()) {
			if (uri.equals(resource.getURI())) {
				dataset.getResourceSet().getResources().remove(resource);
				return;
			}
		}
	}
}
