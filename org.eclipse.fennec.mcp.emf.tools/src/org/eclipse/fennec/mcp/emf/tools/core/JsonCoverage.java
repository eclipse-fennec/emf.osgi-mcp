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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.ExtendedMetaData;

/**
 * Compares a JSON payload against the object graph a codec produced from it, and
 * reports what did not make it across.
 * <p>
 * Deliberately independent of the codec: it takes an already-built {@link EObject}
 * tree, so the comparison logic — name resolution, path building, recursion into
 * containment — is exercised in plain unit tests against hand-built models,
 * without the Fennec codec having to be present.
 * <p>
 * <b>It under-reports rather than over-reports.</b> Two deliberate concessions:
 * <ul>
 * <li>A key is treated as matched if it equals either the feature's plain name or
 * its {@link ExtendedMetaData} name, even though the codec is loaded with
 * {@code useNamesFromExtendedMetadata} and may accept only one of the two.
 * Accepting both cannot produce a false "unmatched" claim, which is the one thing
 * an agent must be able to trust.</li>
 * <li>A value is only reported as dropped when the feature is {@code null} or an
 * empty collection afterwards. An attribute that fell back to its type default
 * ({@code 0}, {@code false}) is indistinguishable from one deliberately set to
 * that default without comparing converted values, so it is not flagged.</li>
 * </ul>
 *
 * @author ilenia
 * @since Sep 3, 2026
 */
public final class JsonCoverage {

	private static final String ROOT_PATH = "$";

	private JsonCoverage() {
	}

	/**
	 * Analyses one loaded payload.
	 *
	 * @param root the object the payload was loaded into
	 * @param data the payload as passed to the codec
	 * @return the coverage report, never {@code null}
	 */
	public static JsonLoadReport analyse(EObject root, Map<String, Object> data) {
		return analyse(root, data, List.of());
	}

	/**
	 * Analyses one loaded payload, carrying the codec's own diagnostics into the
	 * report.
	 *
	 * @param root             the object the payload was loaded into
	 * @param data             the payload as passed to the codec
	 * @param codecDiagnostics messages the codec reported while loading
	 * @return the coverage report, never {@code null}
	 */
	public static JsonLoadReport analyse(EObject root, Map<String, Object> data, List<String> codecDiagnostics) {
		Walk walk = new Walk();
		walk.visit(root, data, ROOT_PATH);
		return new JsonLoadReport(walk.matchedKeys, List.copyOf(walk.unmatched), List.copyOf(walk.dropped),
				walk.unsetFeatures(), List.copyOf(codecDiagnostics));
	}

	/**
	 * Resolves a JSON key against a class, honouring {@link ExtendedMetaData} wire
	 * names. The plain name is tried first: it is the common case and avoids
	 * walking every feature.
	 *
	 * @param eClass the class to resolve against
	 * @param key    the JSON key
	 * @return the matching feature, or {@code null} if the class has none
	 */
	static EStructuralFeature resolveFeature(EClass eClass, String key) {
		EStructuralFeature direct = eClass.getEStructuralFeature(key);
		if (direct != null) {
			return direct;
		}
		for (EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
			if (key.equals(ExtendedMetaData.INSTANCE.getName(feature))) {
				return feature;
			}
		}
		return null;
	}

	/**
	 * Mutable traversal state. Kept in one object so the recursion stays a plain
	 * method rather than threading five accumulators through every call.
	 */
	private static final class Walk {

		private final List<String> unmatched = new ArrayList<>();
		private final List<String> dropped = new ArrayList<>();
		private final Map<EClass, Set<EStructuralFeature>> mentioned = new LinkedHashMap<>();
		private int matchedKeys;

		private void visit(EObject eObject, Map<String, Object> data, String path) {
			EClass eClass = eObject.eClass();
			Set<EStructuralFeature> mentionedHere = mentioned.computeIfAbsent(eClass, key -> new LinkedHashSet<>());
			for (Map.Entry<String, Object> entry : data.entrySet()) {
				String childPath = path + "." + entry.getKey();
				EStructuralFeature feature = resolveFeature(eClass, entry.getKey());
				if (feature == null) {
					unmatched.add(childPath);
					continue;
				}
				matchedKeys++;
				mentionedHere.add(feature);
				visitValue(eObject, feature, entry.getValue(), childPath);
			}
		}

		private void visitValue(EObject eObject, EStructuralFeature feature, Object value, String path) {
			Object loaded = eObject.eGet(feature);
			if (isContainment(feature) && value instanceof Map<?, ?> childData) {
				// A single nested object; a many-valued feature holding one object is
				// what the codec produces for a payload that omitted the array.
				EObject child = firstEObject(loaded);
				if (child == null) {
					dropped.add(path);
				} else {
					visit(child, typed(childData), path);
				}
				return;
			}
			if (isContainment(feature) && value instanceof List<?> childList) {
				visitList(loaded, childList, path);
				return;
			}
			if (isProvided(value) && isEmpty(loaded)) {
				dropped.add(path);
			}
		}

		private void visitList(Object loaded, List<?> childList, String path) {
			List<?> children = loaded instanceof List<?> list ? list : List.of();
			for (int index = 0; index < childList.size(); index++) {
				String childPath = String.format("%s[%d]", path, index);
				if (!(childList.get(index) instanceof Map<?, ?> childData)) {
					// A non-object entry in a containment list: not a nested payload to
					// walk into, and not something this report can judge.
					continue;
				}
				if (index >= children.size() || !(children.get(index) instanceof EObject child)) {
					dropped.add(childPath);
					continue;
				}
				visit(child, typed(childData), childPath);
			}
		}

		/**
		 * The features of every visited class that no key of the payload mentioned,
		 * as {@code Class.feature}. Derived, transient and unchangeable features are
		 * left out for the same reason {@link EClassDescriber} leaves them out: they
		 * are not something a payload could have carried.
		 */
		private List<String> unsetFeatures() {
			List<String> result = new ArrayList<>();
			for (Map.Entry<EClass, Set<EStructuralFeature>> entry : mentioned.entrySet()) {
				for (EStructuralFeature feature : entry.getKey().getEAllStructuralFeatures()) {
					if (!feature.isChangeable() || feature.isDerived() || feature.isTransient()) {
						continue;
					}
					if (!entry.getValue().contains(feature)) {
						result.add(entry.getKey().getName() + "." + feature.getName());
					}
				}
			}
			return result;
		}

		@SuppressWarnings("unchecked")
		private static Map<String, Object> typed(Map<?, ?> data) {
			return (Map<String, Object>) data;
		}

		private static boolean isContainment(EStructuralFeature feature) {
			return feature instanceof EReference reference && reference.isContainment();
		}

		private static EObject firstEObject(Object loaded) {
			if (loaded instanceof EObject eObject) {
				return eObject;
			}
			if (loaded instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof EObject eObject) {
				return eObject;
			}
			return null;
		}

		/** Whether the payload actually offered a value to place. */
		private static boolean isProvided(Object value) {
			if (value == null) {
				return false;
			}
			if (value instanceof Collection<?> collection) {
				return !collection.isEmpty();
			}
			if (value instanceof Map<?, ?> map) {
				return !map.isEmpty();
			}
			return true;
		}

		/** Whether the feature holds nothing at all after the load. */
		private static boolean isEmpty(Object loaded) {
			if (loaded == null) {
				return true;
			}
			return loaded instanceof Collection<?> collection && collection.isEmpty();
		}
	}
}
