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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.XMLResource;
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
