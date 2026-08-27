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
package org.eclipse.fennec.mcp.metadata.tools.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EParameter;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;

/**
 * Collects the annotation vocabularies present across registered packages.
 * <p>
 * The metadata index can query <em>by</em> annotation source and key, but has no
 * method to enumerate which sources and keys exist — so this is a walk over
 * {@code MetadataRegistry.getPackages()} rather than an index lookup. It is
 * O(model) and adds no new indexing, which is fine for a tool called once or
 * twice per task, and it is what lets an agent start from zero: an annotation
 * source it has to guess is a source it will get wrong, silently.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
public final class AnnotationScanner {

	private AnnotationScanner() {
		// static helpers
	}

	/**
	 * @param packages the registered package versions to scan
	 * @return one entry per distinct annotation source, ordered by source
	 */
	public static List<Map<String, Object>> scan(List<PackageMetadata> packages) {
		Map<String, Source> bySource = new TreeMap<>();
		for (PackageMetadata packageMetadata : packages) {
			EPackage ePackage = packageMetadata.getEPackage();
			if (ePackage == null) {
				continue;
			}
			String nsURI = ePackage.getNsURI();
			String origin = MetadataViews.origin(packageMetadata);
			collect(bySource, ePackage, "package", nsURI, origin);
			for (EClassifier eClassifier : ePackage.getEClassifiers()) {
				collectClassifier(bySource, eClassifier, nsURI, origin);
			}
		}
		List<Map<String, Object>> rendered = new ArrayList<>(bySource.size());
		for (Source source : bySource.values()) {
			rendered.add(source.render());
		}
		return rendered;
	}

	private static void collectClassifier(Map<String, Source> bySource, EClassifier eClassifier, String nsURI,
			String origin) {
		if (eClassifier instanceof EClass eClass) {
			collect(bySource, eClass, "class", nsURI, origin);
			for (EStructuralFeature feature : eClass.getEStructuralFeatures()) {
				collect(bySource, feature, "feature", nsURI, origin);
			}
			for (EOperation operation : eClass.getEOperations()) {
				collect(bySource, operation, "operation", nsURI, origin);
				for (EParameter parameter : operation.getEParameters()) {
					collect(bySource, parameter, "parameter", nsURI, origin);
				}
			}
		} else if (eClassifier instanceof EEnum eEnum) {
			collect(bySource, eEnum, "enum", nsURI, origin);
			for (EEnumLiteral literal : eEnum.getELiterals()) {
				collect(bySource, literal, "enumLiteral", nsURI, origin);
			}
		} else if (eClassifier instanceof EDataType eDataType) {
			collect(bySource, eDataType, "dataType", nsURI, origin);
		}
	}

	private static void collect(Map<String, Source> bySource, EModelElement element, String kind, String nsURI,
			String origin) {
		for (EAnnotation annotation : element.getEAnnotations()) {
			if (annotation.getSource() == null) {
				continue;
			}
			Source source = bySource.computeIfAbsent(annotation.getSource(), Source::new);
			source.hits++;
			source.keys.addAll(annotation.getDetails().keySet());
			source.nsURIs.add(nsURI);
			source.origins.add(origin);
			source.elementKinds.merge(kind, 1, Integer::sum);
		}
	}

	private static final class Source {

		private final String source;
		private final TreeSet<String> keys = new TreeSet<>();
		private final TreeSet<String> nsURIs = new TreeSet<>();
		private final TreeSet<String> origins = new TreeSet<>();
		private final Map<String, Integer> elementKinds = new TreeMap<>();
		private int hits;

		private Source(String source) {
			this.source = source;
		}

		private Map<String, Object> render() {
			Map<String, Object> rendered = new LinkedHashMap<>();
			rendered.put("annotationSource", source);
			rendered.put("keys", List.copyOf(keys));
			rendered.put("hits", hits);
			rendered.put("nsURIs", List.copyOf(nsURIs));
			rendered.put("origins", List.copyOf(origins));
			rendered.put("elementKinds", Map.copyOf(elementKinds));
			return rendered;
		}
	}
}
