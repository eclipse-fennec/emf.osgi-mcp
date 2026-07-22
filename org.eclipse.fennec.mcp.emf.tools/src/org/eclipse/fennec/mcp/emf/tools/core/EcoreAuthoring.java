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

import java.util.Iterator;
import java.util.List;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.ETypedElement;

/**
 * Shared helpers for the reflective Ecore <i>authoring</i> tools: resolving a
 * classifier reference (a registry/built-in {@code <nsURI>#//<Name>} handled by
 * the {@link ClassifierResolver}, or a dataset-local objectId of a classifier
 * authored in the same dataset), fetching typed owner objects, registering a
 * new object under a dataset id (with cap enforcement) and the register-time
 * guards (dynamic-only, validity).
 * <p>
 * Authoring objects are not recorded as recipe operations — the persistence
 * contract for a metamodel is its XMI (see {@code export_dataset} /
 * {@code import_ecore}).
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
public final class EcoreAuthoring {

	private EcoreAuthoring() {
	}

	/**
	 * The structural-feature flags of the EMF generic editor.
	 */
	public record FeatureFlags(int lowerBound, int upperBound, boolean ordered, boolean unique,
			boolean changeable, boolean isVolatile, boolean isTransient, boolean unsettable, boolean derived) {
	}

	/**
	 * Registers a freshly created authoring object under a new dataset id.
	 *
	 * @return the dataset-local id
	 */
	public static String put(Dataset dataset, EObject eObject, DatasetLimits limits) {
		synchronized (dataset) {
			if (dataset.objectCount() + 1 > limits.maxObjectsPerDataset()) {
				throw new ToolException(String.format("Dataset object limit of %d reached", limits.maxObjectsPerDataset()));
			}
			String id = dataset.nextObjectId();
			dataset.putObject(id, eObject);
			return id;
		}
	}

	/**
	 * Resolves a type reference to a classifier: a {@code <nsURI>#//<Name>}
	 * identifier via the registry/built-in {@code resolver}, or a dataset-local
	 * objectId of a classifier authored in the same dataset.
	 */
	public static EClassifier resolveClassifier(Dataset dataset, ClassifierResolver resolver, String ref) {
		if (ref == null || ref.isBlank()) {
			throw new ToolException("A type reference must be a non-empty string (a <nsURI>#//<Name> identifier or a dataset objectId)");
		}
		if (ref.contains(ModelGuard.CLASS_REF_SEPARATOR)) {
			return resolver.resolveClassifier(ref);
		}
		EObject target = dataset.requireObject(ref);
		if (!(target instanceof EClassifier classifier)) {
			throw new ToolException(String.format("Object '%s' is a %s, not a classifier usable as a type", ref, target.eClass().getName()));
		}
		return classifier;
	}

	/**
	 * Resolves a type reference that must denote an {@link EClass} (super type).
	 */
	public static EClass resolveEClass(Dataset dataset, ClassifierResolver resolver, String ref) {
		EClassifier classifier = resolveClassifier(dataset, resolver, ref);
		if (!(classifier instanceof EClass eClass)) {
			throw new ToolException(String.format("'%s' is a %s, not an EClass", ref, classifier.eClass().getName()));
		}
		return eClass;
	}

	public static EPackage requireEPackage(Dataset dataset, String objectId) {
		return require(dataset, objectId, EPackage.class, "EPackage");
	}

	public static EClass requireEClass(Dataset dataset, String objectId) {
		return require(dataset, objectId, EClass.class, "EClass");
	}

	public static EEnum requireEEnum(Dataset dataset, String objectId) {
		return require(dataset, objectId, EEnum.class, "EEnum");
	}

	public static EOperation requireEOperation(Dataset dataset, String objectId) {
		return require(dataset, objectId, EOperation.class, "EOperation");
	}

	public static EModelElement requireEModelElement(Dataset dataset, String objectId) {
		return require(dataset, objectId, EModelElement.class, "EModelElement");
	}

	private static <T> T require(Dataset dataset, String objectId, Class<T> type, String label) {
		EObject eObject = dataset.requireObject(objectId);
		if (!type.isInstance(eObject)) {
			throw new ToolException(String.format("Object '%s' is a %s, not an %s", objectId, eObject.eClass().getName(), label));
		}
		return type.cast(eObject);
	}

	/**
	 * Applies the type of a typed element from either a plain classifier
	 * reference ({@code eType}) or a {@code GenericType} spec ({@code eGenericType},
	 * from which EMF derives {@code eType}). At most one may be given.
	 */
	public static void applyType(ETypedElement typed, String eTypeRef, Object eGenericTypeSpec, boolean required,
			Dataset dataset, ClassifierResolver resolver) {
		if (eGenericTypeSpec != null) {
			if (eTypeRef != null) {
				throw new ToolException("Set only one of 'eType' and 'eGenericType'");
			}
			typed.setEGenericType(GenericTypes.parse(dataset, resolver, eGenericTypeSpec));
		} else if (eTypeRef != null) {
			typed.setEType(resolveClassifier(dataset, resolver, eTypeRef));
		} else if (required) {
			throw new ToolException("Either 'eType' or 'eGenericType' is required");
		}
	}

	/**
	 * Applies the common structural-feature flags.
	 */
	public static void applyFlags(EStructuralFeature feature, FeatureFlags flags) {
		feature.setLowerBound(flags.lowerBound());
		feature.setUpperBound(flags.upperBound());
		feature.setOrdered(flags.ordered());
		feature.setUnique(flags.unique());
		feature.setChangeable(flags.changeable());
		feature.setVolatile(flags.isVolatile());
		feature.setTransient(flags.isTransient());
		feature.setUnsettable(flags.unsettable());
		feature.setDerived(flags.derived());
	}

	/**
	 * Rejects a package that carries a Java instance class on any classifier:
	 * such a package would make EMF resolve/instantiate an arbitrary in-process
	 * class (a code-execution vector the Diagnostician does not catch), so
	 * registered packages must be <b>dynamic</b>.
	 */
	public static void requireDynamic(EPackage ePackage) {
		for (Iterator<EObject> it = ePackage.eAllContents(); it.hasNext();) {
			EObject eObject = it.next();
			if (eObject instanceof EClassifier classifier
					&& (notBlank(classifier.getInstanceClassName()) || notBlank(classifier.getInstanceTypeName()))) {
				throw new ToolException(String.format(
						"Classifier '%s' declares an instance class; registered packages must be dynamic (no instanceClassName/instanceTypeName)",
						classifier.getName()));
			}
		}
	}

	/**
	 * Wires super types onto an EClass from a list of type references.
	 */
	public static void addSuperTypes(Dataset dataset, EClass eClass, List<String> superTypeRefs, ClassifierResolver resolver) {
		if (superTypeRefs == null) {
			return;
		}
		for (String ref : superTypeRefs) {
			eClass.getESuperTypes().add(resolveEClass(dataset, resolver, ref));
		}
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
