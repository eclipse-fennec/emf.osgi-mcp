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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.ETypedElement;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;

/**
 * Builds a compact, agent-friendly description of an {@link EClass} and its
 * settable structural features. The description is intentionally reflective
 * (not a JSON schema): it distinguishes containment from cross-references and
 * carries multiplicity, defaults, enum literals and documentation — exactly
 * what an agent needs to drive {@code create_instance}/{@code modify_feature}.
 * <p>
 * It also carries what an agent needs in order to <i>copy</i> a model rather
 * than instantiate it: the EAnnotations of the class and of every feature in
 * their exact spelling, the declared supertypes apart from the inherited ones
 * and both as {@code <nsURI>#//<Name>}, and which features are inherited. Those
 * were the three gaps that forced a whole {@code .ecore} to be fetched through
 * {@code export_package} just to read the conventions of two classes.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public final class EClassDescriber {

	private EClassDescriber() {
	}

	/**
	 * Describes the given class including all settable features.
	 *
	 * @param eClass     the class to describe
	 * @param guard      the guard, used to mark whether referenced types are instantiable
	 * @param visibility which annotation sources may be shown
	 * @return an ordered map representation of the class description
	 */
	public static Map<String, Object> describe(EClass eClass, ModelGuard guard, AnnotationVisibility visibility) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("name", eClass.getName());
		result.put("eClass", ModelGuard.refOf(eClass));
		result.put("package", eClass.getEPackage().getNsURI());
		result.put("abstract", eClass.isAbstract() || eClass.isInterface());
		// Declared and inherited kept apart, and both qualified: an agent copying a
		// convention needs to know which supertype this class actually names, and a
		// bare 'AbstractItem' cannot be fed back into any tool nor tell the agent
		// which package it came from.
		putIfAny(result, "superTypes", refsOf(eClass.getESuperTypes()));
		putIfAny(result, "allSuperTypes", refsOf(eClass.getEAllSuperTypes()));
		String documentation = EcoreUtil.getDocumentation(eClass);
		if (documentation != null && !documentation.isBlank()) {
			result.put("documentation", documentation);
		}
		describeAnnotationsInto(result, eClass, visibility);
		List<Map<String, Object>> features = new ArrayList<>();
		for (EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
			if (!feature.isChangeable() || feature.isDerived() || feature.isTransient()) {
				continue;
			}
			features.add(describeFeature(eClass, feature, guard, visibility));
		}
		result.put("features", features);
		return result;
	}

	/**
	 * The EAnnotations of one element in their exact spelling, as
	 * {@code [{ "source": ..., "details": { ... } }]}.
	 * <p>
	 * This is the reason an agent had to fetch a whole {@code .ecore} to learn a
	 * model family's conventions: {@code ExtendedMetaData} wire names, provider
	 * metadata and any project-specific source were invisible here, while
	 * {@link EcoreUtil#getDocumentation} surfaced the single GenModel
	 * {@code documentation} key. That key stays where it was, so it appears both
	 * as {@code documentation} and in this list — faithfulness of the list is
	 * worth the overlap, since copying a convention means copying the source URI
	 * and key exactly.
	 */
	private static void describeAnnotationsInto(Map<String, Object> result, EModelElement element,
			AnnotationVisibility visibility) {
		List<Map<String, Object>> described = new ArrayList<>();
		int hidden = 0;
		for (EAnnotation annotation : element.getEAnnotations()) {
			if (!visibility.isSourceVisible(annotation.getSource())) {
				hidden++;
				continue;
			}
			Map<String, Object> one = new LinkedHashMap<>();
			// A null or empty source occurs in the wild; report it as-is rather than
			// keying the list by source and silently merging those together.
			one.put("source", annotation.getSource());
			Map<String, Object> details = new LinkedHashMap<>();
			for (Map.Entry<String, String> detail : annotation.getDetails()) {
				details.put(detail.getKey(), detail.getValue());
			}
			if (!details.isEmpty()) {
				one.put("details", details);
			}
			described.add(one);
		}
		putIfAny(result, "annotations", described);
		if (hidden > 0) {
			// Counted, never named — the same rule export_package applies to denied
			// classes. Naming a withheld source would disclose the thing the
			// deny-list exists to withhold, while a count keeps the description
			// honest about not being complete.
			result.put("hiddenAnnotations", hidden);
		}
	}

	private static Map<String, Object> describeFeature(EClass eClass, EStructuralFeature feature, ModelGuard guard,
			AnnotationVisibility visibility) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("name", feature.getName());
		result.put("kind", kindOf(feature));
		result.put("type", typeOf(feature));
		result.put("many", feature.isMany());
		result.put("required", feature.getLowerBound() > 0);
		result.put("lowerBound", feature.getLowerBound());
		result.put("upperBound", feature.getUpperBound() == ETypedElement.UNBOUNDED_MULTIPLICITY ? -1 : feature.getUpperBound());
		if (feature instanceof EAttribute attribute) {
			if (attribute.isID()) {
				result.put("id", true);
			}
			Object defaultValue = attribute.getDefaultValue();
			if (defaultValue != null) {
				result.put("defaultValue", String.valueOf(defaultValue));
			}
			if (attribute.getEAttributeType() instanceof EEnum eEnum) {
				result.put("enumLiterals", eEnum.getELiterals().stream().map(EEnumLiteral::getLiteral).toList());
			}
		}
		if (feature instanceof EReference reference && reference.getEReferenceType() != null) {
			result.put("instantiable", guard.isClassAllowed(reference.getEReferenceType()));
		}
		String documentation = EcoreUtil.getDocumentation(feature);
		if (documentation != null && !documentation.isBlank()) {
			result.put("documentation", documentation);
		}
		// Without this an agent re-declares inherited features on its own subclass,
		// because getEAllStructuralFeatures flattens the hierarchy away.
		EClass declaring = feature.getEContainingClass();
		if (declaring != null && !declaring.equals(eClass)) {
			result.put("inherited", Boolean.TRUE);
			result.put("declaringClass", ModelGuard.refOf(declaring));
		}
		describeAnnotationsInto(result, feature, visibility);
		return result;
	}

	private static List<String> refsOf(List<EClass> classes) {
		return classes.stream().map(ModelGuard::refOf).toList();
	}

	private static void putIfAny(Map<String, Object> result, String key, List<?> values) {
		if (!values.isEmpty()) {
			result.put(key, values);
		}
	}

	private static String kindOf(EStructuralFeature feature) {
		if (feature instanceof EReference reference) {
			return reference.isContainment() ? "containment" : "reference";
		}
		return "attribute";
	}

	private static String typeOf(EStructuralFeature feature) {
		if (feature instanceof EReference reference && reference.getEReferenceType() != null) {
			return ModelGuard.refOf(reference.getEReferenceType());
		}
		return feature.getEType() != null ? feature.getEType().getName() : "unknown";
	}
}
