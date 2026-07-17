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

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.ETypedElement;
import org.eclipse.emf.ecore.util.EcoreUtil;

/**
 * Builds a compact, agent-friendly description of an {@link EClass} and its
 * settable structural features. The description is intentionally reflective
 * (not a JSON schema): it distinguishes containment from cross-references and
 * carries multiplicity, defaults, enum literals and documentation — exactly
 * what an agent needs to drive {@code create_instance}/{@code modify_feature}.
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
	 * @param eClass the class to describe
	 * @param guard  the guard, used to mark whether referenced types are instantiable
	 * @return an ordered map representation of the class description
	 */
	public static Map<String, Object> describe(EClass eClass, ModelGuard guard) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("name", eClass.getName());
		result.put("eClass", ModelGuard.refOf(eClass));
		result.put("package", eClass.getEPackage().getNsURI());
		result.put("abstract", eClass.isAbstract() || eClass.isInterface());
		List<String> superTypes = eClass.getEAllSuperTypes().stream().map(EClass::getName).toList();
		if (!superTypes.isEmpty()) {
			result.put("superTypes", superTypes);
		}
		String documentation = EcoreUtil.getDocumentation(eClass);
		if (documentation != null && !documentation.isBlank()) {
			result.put("documentation", documentation);
		}
		List<Map<String, Object>> features = new ArrayList<>();
		for (EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
			if (!feature.isChangeable() || feature.isDerived() || feature.isTransient()) {
				continue;
			}
			features.add(describeFeature(feature, guard));
		}
		result.put("features", features);
		return result;
	}

	private static Map<String, Object> describeFeature(EStructuralFeature feature, ModelGuard guard) {
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
		return result;
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
