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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.emf.osgi.model.metadata.AspectEntry;
import org.eclipse.fennec.emf.osgi.model.metadata.ClassMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.FeatureMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.OperationMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.MetadataDiagnostic;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;

/**
 * Renders {@link AspectEntry} contents into plain JSON-shaped maps by walking
 * their {@code EClass} reflectively.
 * <p>
 * Reflection rather than the Fennec codec on purpose. {@code AspectEntry.content}
 * is a bare {@code EObject} contributed by whichever {@code MetadataHandler}
 * built it; serializing it through the codec would require that aspect's own
 * EPackage to be known to the {@code MetadataService} and would couple a
 * deliberately generic tool to one specific aspect provider. A reflective walk
 * has no such dependency and holds for aspect types that do not exist yet.
 * <p>
 * {@code transientContent} is a non-EMF payload and cannot be rendered; its
 * presence and Java class name are reported instead. {@code diagnostics} are
 * always carried through — an entry's diagnostics are how a provider reports
 * that an aspect <em>failed to build</em> (a misplaced or misspelled annotation,
 * say), and they are aggregated nowhere else.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
public final class AspectRenderer {

	/**
	 * Containment depth beyond which children are reported as a type reference
	 * only. Aspect contents are configuration trees a few levels deep; the cap is
	 * a guard against a pathological contributor, not an expected case.
	 */
	private static final int MAX_DEPTH = 8;

	private AspectRenderer() {
		// static helpers
	}

	/**
	 * @param aspects      the aspect entries of a package, class, feature or operation
	 * @param aspectTypeId the aspect type to keep, or {@code null} for all of them
	 * @param visibility   which aspect types may be shown; a withheld type is
	 *                     rendered for nobody, whether or not it was asked for by
	 *                     name
	 * @return the rendered entries, ordered by type id
	 */
	public static List<Map<String, Object>> render(EList<AspectEntry> aspects, String aspectTypeId,
			AnnotationVisibility visibility) {
		List<Map<String, Object>> rendered = new ArrayList<>();
		if (aspects == null) {
			return rendered;
		}
		for (AspectEntry entry : aspects) {
			// An aspect is the parsed form of annotations and carries no source, so
			// the annotation-source deny-list cannot reach it: without this, a denied
			// 'codec' source is still readable as a 'codec' aspect, which is the
			// class's serialization configuration in full.
			if (!visibility.isAspectTypeVisible(entry.getTypeId())) {
				continue;
			}
			if (aspectTypeId == null || aspectTypeId.equals(entry.getTypeId())) {
				rendered.add(renderEntry(entry));
			}
		}
		rendered.sort(Comparator.comparing(entry -> String.valueOf(entry.get("typeId"))));
		return rendered;
	}

	/**
	 * Counts the aspect entries present across registered packages, by type id and
	 * by the kind of element carrying them. The metadata layer has no registry of
	 * aspect types, so this is a walk; it is what makes {@code list_aspects} answer
	 * "what aspect vocabularies exist here" without any of them being known in
	 * advance.
	 *
	 * @param packages   the registered package versions
	 * @param visibility which aspect types may be shown
	 * @return aspect type id to element kind to count, both keys ordered
	 */
	public static Map<String, Map<String, Integer>> summarize(List<PackageMetadata> packages,
			AnnotationVisibility visibility) {
		Map<String, Map<String, Integer>> byTypeId = new TreeMap<>();
		for (PackageMetadata packageMetadata : packages) {
			count(byTypeId, packageMetadata.getAspects(), "package");
			for (ClassMetadata classMetadata : packageMetadata.getClasses()) {
				count(byTypeId, classMetadata.getAspects(), "class");
				for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
					count(byTypeId, featureMetadata.getAspects(), "feature");
				}
				for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
					count(byTypeId, operationMetadata.getAspects(), "operation");
				}
			}
		}
		byTypeId.keySet().removeIf(typeId -> !visibility.isAspectTypeVisible(typeId));
		return byTypeId;
	}

	private static void count(Map<String, Map<String, Integer>> byTypeId, EList<AspectEntry> aspects, String kind) {
		if (aspects == null) {
			return;
		}
		for (AspectEntry entry : aspects) {
			if (entry.getTypeId() != null) {
				byTypeId.computeIfAbsent(entry.getTypeId(), id -> new TreeMap<>()).merge(kind, 1, Integer::sum);
			}
		}
	}

	private static Map<String, Object> renderEntry(AspectEntry entry) {
		Map<String, Object> rendered = new LinkedHashMap<>();
		rendered.put("typeId", entry.getTypeId());
		EObject content = entry.getContent();
		if (content != null) {
			rendered.put("contentType", MetadataViews.typeReference(content.eClass()));
			rendered.put("content", renderContent(content, 0));
		}
		Object transientContent = entry.getTransientContent();
		if (transientContent != null) {
			Map<String, Object> opaque = new LinkedHashMap<>();
			opaque.put("present", true);
			opaque.put("javaClass", transientContent.getClass().getName());
			opaque.put("note", "Not an EMF object, so it cannot be rendered here.");
			rendered.put("transientContent", opaque);
		}
		rendered.put("diagnostics", renderDiagnostics(entry.getDiagnostics()));
		return rendered;
	}

	private static List<Map<String, Object>> renderDiagnostics(EList<MetadataDiagnostic> diagnostics) {
		List<Map<String, Object>> rendered = new ArrayList<>();
		if (diagnostics == null) {
			return rendered;
		}
		for (MetadataDiagnostic diagnostic : diagnostics) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("severity", diagnostic.getSeverity() == null ? null : diagnostic.getSeverity().getName());
			entry.put("key", diagnostic.getKey());
			entry.put("message", diagnostic.getMessage());
			rendered.add(entry);
		}
		return rendered;
	}

	/**
	 * Walks one aspect content object. Derived features are skipped: they are
	 * computed views whose evaluation is the contributor's business, not a fact
	 * about how the aspect was configured.
	 */
	private static Map<String, Object> renderContent(EObject content, int depth) {
		Map<String, Object> rendered = new LinkedHashMap<>();
		for (EStructuralFeature feature : content.eClass().getEAllStructuralFeatures()) {
			if (feature.isDerived()) {
				continue;
			}
			Object value = content.eGet(feature);
			if (value == null) {
				continue;
			}
			if (feature.isMany()) {
				List<?> values = (List<?>) value;
				if (values.isEmpty()) {
					continue;
				}
				List<Object> items = new ArrayList<>(values.size());
				for (Object item : values) {
					items.add(renderValue(feature, item, depth));
				}
				rendered.put(feature.getName(), items);
			} else {
				rendered.put(feature.getName(), renderValue(feature, value, depth));
			}
		}
		return rendered;
	}

	private static Object renderValue(EStructuralFeature feature, Object value, int depth) {
		if (value == null) {
			return null;
		}
		if (feature instanceof EReference reference) {
			if (!(value instanceof EObject child)) {
				return String.valueOf(value);
			}
			if (reference.isContainment() && depth < MAX_DEPTH) {
				return renderContent(child, depth + 1);
			}
			return objectReference(child);
		}
		EDataType type = feature instanceof EAttribute attribute ? attribute.getEAttributeType() : null;
		return renderScalar(type, value);
	}

	private static Object renderScalar(EDataType type, Object value) {
		if (value instanceof Enumerator enumerator) {
			return enumerator.getName();
		}
		if (value instanceof String || value instanceof Boolean || value instanceof Number) {
			return value;
		}
		if (type != null) {
			try {
				return EcoreUtil.convertToString(type, value);
			} catch (RuntimeException e) {
				// A data type without a working converter (EJavaObject and friends);
				// the plain string form is still more useful to the agent than nothing.
				return String.valueOf(value);
			}
		}
		return String.valueOf(value);
	}

	/**
	 * @param eObject a non-contained object
	 * @return an {@code <nsURI>#//<Name>} reference where the object is part of a
	 *         metamodel, its EMF URI otherwise
	 */
	private static String objectReference(EObject eObject) {
		if (eObject instanceof EClassifier eClassifier) {
			return MetadataViews.typeReference(eClassifier);
		}
		if (eObject instanceof EStructuralFeature feature) {
			return member(MetadataViews.typeReference(feature.getEContainingClass()), feature.getName());
		}
		if (eObject instanceof EOperation operation) {
			return member(MetadataViews.typeReference(operation.getEContainingClass()), operation.getName());
		}
		return EcoreUtil.getURI(eObject).toString();
	}

	private static String member(String owner, String name) {
		return owner == null ? name : owner + "/" + name;
	}
}
