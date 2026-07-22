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

import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EGenericType;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.ETypeParameter;
import org.eclipse.emf.ecore.EcoreFactory;

/**
 * Parses the recursive {@code GenericType} JSON shape into an
 * {@link EGenericType} (1:1 with EMF generics):
 *
 * <pre>
 * { "classifier": "&lt;ref|objectId&gt;",        // EGenericType.eClassifier
 *   "typeParameter": "&lt;ETypeParameter id&gt;",  // EGenericType.eTypeParameter
 *   "typeArguments": [ GenericType, ... ],    // only with classifier
 *   "upperBound": GenericType,                // wildcard "? extends X"
 *   "lowerBound": GenericType }               // wildcard "? super X"
 * </pre>
 *
 * At most one of {@code classifier}/{@code typeParameter} may be set; neither
 * set is a pure wildcard (optionally bounded). {@code typeArguments} require a
 * {@code classifier}.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
public final class GenericTypes {

	private GenericTypes() {
	}

	public static EGenericType parse(Dataset dataset, ClassifierResolver resolver, Object spec) {
		if (!(spec instanceof Map<?, ?> map)) {
			throw new ToolException("A generic type must be a JSON object with optional classifier/typeParameter/typeArguments/upperBound/lowerBound");
		}
		String classifierRef = string(map, "classifier");
		String typeParameterId = string(map, "typeParameter");
		if (classifierRef != null && typeParameterId != null) {
			throw new ToolException("A generic type must set at most one of 'classifier' and 'typeParameter'");
		}
		EGenericType genericType = EcoreFactory.eINSTANCE.createEGenericType();
		if (classifierRef != null) {
			genericType.setEClassifier(EcoreAuthoring.resolveClassifier(dataset, resolver, classifierRef));
			Object typeArguments = map.get("typeArguments");
			if (typeArguments != null) {
				if (!(typeArguments instanceof List<?> list)) {
					throw new ToolException("'typeArguments' must be an array of generic types");
				}
				for (Object argument : list) {
					genericType.getETypeArguments().add(parse(dataset, resolver, argument));
				}
			}
		} else if (typeParameterId != null) {
			EObject target = dataset.requireObject(typeParameterId);
			if (!(target instanceof ETypeParameter typeParameter)) {
				throw new ToolException(String.format("'%s' is a %s, not an ETypeParameter", typeParameterId, target.eClass().getName()));
			}
			genericType.setETypeParameter(typeParameter);
			if (map.containsKey("typeArguments")) {
				throw new ToolException("'typeArguments' is only valid together with 'classifier'");
			}
		} else if (map.containsKey("typeArguments")) {
			throw new ToolException("'typeArguments' requires a 'classifier'");
		}
		Object upperBound = map.get("upperBound");
		if (upperBound != null) {
			genericType.setEUpperBound(parse(dataset, resolver, upperBound));
		}
		Object lowerBound = map.get("lowerBound");
		if (lowerBound != null) {
			genericType.setELowerBound(parse(dataset, resolver, lowerBound));
		}
		return genericType;
	}

	private static String string(Map<?, ?> map, String key) {
		Object value = map.get(key);
		return value instanceof String string && !string.isBlank() ? string : null;
	}
}
