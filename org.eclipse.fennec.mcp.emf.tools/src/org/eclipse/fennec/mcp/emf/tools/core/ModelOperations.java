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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.ETypedElement;
import org.eclipse.emf.ecore.util.EcoreUtil;

/**
 * Reflective EMF operations shared by the builder tools and the recipe replay:
 * instance creation, feature set/unset/add/remove with type coercion,
 * reference resolution by dataset-local object id and instance deletion.
 * <p>
 * Every error is raised as a sanitized {@link ToolException} whose message is
 * designed to let the agent self-correct (it names the offending feature and
 * lists alternatives where helpful). Replay re-validates each operation
 * against the current {@link ModelGuard} state, so a recipe can never bypass
 * the allow-list.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public final class ModelOperations {

	private ModelOperations() {
	}

	/**
	 * Creates a new instance of the (allow-listed) class in the dataset and
	 * records the recipe operation.
	 *
	 * @return the dataset-local id of the new object
	 */
	public static String createInstance(Dataset dataset, EClass eClass, DatasetLimits limits) {
		EObject eObject = eClass.getEPackage().getEFactoryInstance().create(eClass);
		// Hold the dataset monitor across check+put+record so a concurrent call on
		// the same dataset cannot slip past the cap (Dataset locks on itself).
		synchronized (dataset) {
			checkObjectCap(dataset, limits, 1);
			checkRecipeCap(dataset, limits);
			String objectId = dataset.nextObjectId();
			dataset.putObject(objectId, eObject);
			dataset.record(RecipeOp.create(objectId, ModelGuard.refOf(eClass)));
			return objectId;
		}
	}

	/**
	 * Applies a single feature modification with dataset-local reference
	 * resolution only (registry classifier references are rejected).
	 *
	 * @param action one of {@code set}, {@code unset}, {@code add}, {@code remove}
	 */
	public static void modifyFeature(Dataset dataset, String objectId, String featureName, String action,
			Object value, Integer index, DatasetLimits limits) {
		modifyFeature(dataset, objectId, featureName, action, value, index, limits, ClassifierResolver.datasetLocalOnly());
	}

	/**
	 * Applies a single feature modification and records the recipe operation.
	 * Reference values that are a class-reference identifier
	 * ({@code <nsURI>#//<Name>}) are resolved through {@code resolver} against
	 * the registry; every other reference value is a dataset-local object id.
	 *
	 * @param action   one of {@code set}, {@code unset}, {@code add}, {@code remove}
	 * @param resolver resolves registry/built-in classifier references
	 */
	public static void modifyFeature(Dataset dataset, String objectId, String featureName, String action,
			Object value, Integer index, DatasetLimits limits, ClassifierResolver resolver) {
		checkRecipeCap(dataset, limits);
		EObject eObject = dataset.requireObject(objectId);
		EStructuralFeature feature = requireFeature(eObject.eClass(), featureName);
		switch (action) {
		case "set" -> {
			if (value == null) {
				eObject.eUnset(feature);
				dataset.record(RecipeOp.unset(objectId, featureName));
			} else {
				applySet(dataset, eObject, feature, value, resolver, limits);
				dataset.record(RecipeOp.set(objectId, featureName, refOrNull(feature, value) == null ? value : null, refOrNull(feature, value)));
			}
		}
		case "unset" -> {
			eObject.eUnset(feature);
			dataset.record(RecipeOp.unset(objectId, featureName));
		}
		case "add" -> {
			applyAdd(dataset, eObject, feature, value, index, resolver, limits);
			dataset.record(RecipeOp.add(objectId, featureName, refOrNull(feature, value) == null ? value : null, refOrNull(feature, value), index));
		}
		case "remove" -> {
			applyRemove(dataset, eObject, feature, value, index, resolver);
			dataset.record(RecipeOp.remove(objectId, featureName, refOrNull(feature, value) == null ? value : null, refOrNull(feature, value), index));
		}
		default -> throw new ToolException(String.format("Unknown action '%s'. Use one of: set, unset, add, remove", action));
		}
	}

	/**
	 * Deletes an object (and its containment subtree) from the dataset,
	 * removing all references to the deleted objects, and records the recipe
	 * operation.
	 */
	public static void deleteInstance(Dataset dataset, String objectId, DatasetLimits limits) {
		checkRecipeCap(dataset, limits);
		doDelete(dataset, objectId);
		dataset.record(RecipeOp.delete(objectId));
	}

	private static void doDelete(Dataset dataset, String objectId) {
		EObject eObject = dataset.requireObject(objectId);
		Set<EObject> deleted = new LinkedHashSet<>();
		deleted.add(eObject);
		eObject.eAllContents().forEachRemaining(deleted::add);
		// dataset objects live outside of resources, so cross-references must be
		// cleared over the dataset roots explicitly (EcoreUtil.delete would only
		// scan the deleted object's own tree)
		List<EObject> scope = dataset.roots();
		Map<EObject, Collection<EStructuralFeature.Setting>> usages = EcoreUtil.UsageCrossReferencer.findAll(deleted, scope);
		for (Map.Entry<EObject, Collection<EStructuralFeature.Setting>> entry : usages.entrySet()) {
			for (EStructuralFeature.Setting setting : entry.getValue()) {
				if (!deleted.contains(setting.getEObject())) {
					EcoreUtil.remove(setting, entry.getKey());
				}
			}
		}
		EcoreUtil.remove(eObject);
		for (EObject toDelete : deleted) {
			String id = dataset.idOf(toDelete);
			if (id != null) {
				dataset.removeObject(id);
			}
		}
	}

	/**
	 * Replays a recipe using the guard's allow-list, resolving both dataset-local
	 * and registry classifier references. Delegates to the
	 * {@link ClassifierResolver} overload.
	 */
	public static void replay(Dataset dataset, List<RecipeOp> recipe, ModelGuard guard, DatasetLimits limits,
			FromJsonLoader fromJsonLoader) {
		replay(dataset, recipe, guard.resolverFor(null), limits, fromJsonLoader);
	}

	/**
	 * Replays a recipe into the dataset without re-recording the operations.
	 * Each {@code create}/{@code fromJson} is re-checked through {@code resolver},
	 * so replay honours the <i>current</i> allow-list and can never bypass it.
	 */
	public static void replay(Dataset dataset, List<RecipeOp> recipe, ClassifierResolver resolver, DatasetLimits limits,
			FromJsonLoader fromJsonLoader) {
		if (recipe.size() > limits.maxRecipeOps()) {
			throw new ToolException(String.format("Recipe exceeds the limit of %d operations", limits.maxRecipeOps()));
		}
		for (RecipeOp op : recipe) {
			switch (op.op()) {
			case RecipeOp.OP_CREATE -> {
				EClass eClass = resolver.resolveConcreteEClass(op.eClass());
				EObject eObject = eClass.getEPackage().getEFactoryInstance().create(eClass);
				synchronized (dataset) {
					checkObjectCap(dataset, limits, 1);
					dataset.putObject(op.objectId(), eObject);
				}
			}
			case RecipeOp.OP_FROM_JSON -> {
				EClass eClass = resolver.resolveConcreteEClass(op.eClass());
				if (!(op.value() instanceof Map<?, ?>)) {
					throw new ToolException("Recipe operation 'fromJson' requires a 'value' object");
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> data = (Map<String, Object>) op.value();
				fromJsonLoader.load(dataset, op.objectId(), eClass, data);
			}
			case RecipeOp.OP_SET -> {
				EObject eObject = dataset.requireObject(op.objectId());
				EStructuralFeature feature = requireFeature(eObject.eClass(), op.feature());
				applySet(dataset, eObject, feature, op.ref() != null ? op.ref() : op.value(), resolver, limits);
			}
			case RecipeOp.OP_UNSET -> {
				EObject eObject = dataset.requireObject(op.objectId());
				eObject.eUnset(requireFeature(eObject.eClass(), op.feature()));
			}
			case RecipeOp.OP_ADD -> {
				EObject eObject = dataset.requireObject(op.objectId());
				EStructuralFeature feature = requireFeature(eObject.eClass(), op.feature());
				applyAdd(dataset, eObject, feature, op.ref() != null ? op.ref() : op.value(), op.index(), resolver, limits);
			}
			case RecipeOp.OP_REMOVE -> {
				EObject eObject = dataset.requireObject(op.objectId());
				EStructuralFeature feature = requireFeature(eObject.eClass(), op.feature());
				applyRemove(dataset, eObject, feature, op.ref() != null ? op.ref() : op.value(), op.index(), resolver);
			}
			case RecipeOp.OP_DELETE -> doDelete(dataset, op.objectId());
			default -> throw new ToolException(String.format("Unknown recipe operation '%s'", op.op()));
			}
		}
	}

	/**
	 * Loader callback used to replay {@code fromJson} operations; the codec
	 * lives behind the tool layer.
	 */
	@FunctionalInterface
	public interface FromJsonLoader {
		void load(Dataset dataset, String objectId, EClass eClass, Map<String, Object> data);
	}

	private static void applySet(Dataset dataset, EObject eObject, EStructuralFeature feature, Object value, ClassifierResolver resolver, DatasetLimits limits) {
		if (feature.isMany()) {
			throw new ToolException(String.format("Feature '%s' is many-valued; use action 'add' instead of 'set'", feature.getName()));
		}
		Object featureValue = toFeatureValue(dataset, feature, value, resolver, limits);
		try {
			eObject.eSet(feature, featureValue);
		} catch (RuntimeException e) {
			throw new ToolException(String.format("Cannot set feature '%s': %s", feature.getName(), e.getMessage()));
		}
	}

	private static void applyAdd(Dataset dataset, EObject eObject, EStructuralFeature feature, Object value, Integer index, ClassifierResolver resolver, DatasetLimits limits) {
		if (!feature.isMany()) {
			throw new ToolException(String.format("Feature '%s' is single-valued; use action 'set' instead of 'add'", feature.getName()));
		}
		@SuppressWarnings("unchecked")
		EList<Object> list = (EList<Object>) eObject.eGet(feature);
		int upperBound = feature.getUpperBound();
		if (upperBound != ETypedElement.UNBOUNDED_MULTIPLICITY && list.size() >= upperBound) {
			throw new ToolException(String.format("Feature '%s' already holds its maximum of %d value(s)", feature.getName(), upperBound));
		}
		Object featureValue = toFeatureValue(dataset, feature, value, resolver, limits);
		try {
			if (index != null) {
				if (index < 0 || index > list.size()) {
					throw new ToolException(String.format("Index %d is out of bounds for feature '%s' (size %d)", index, feature.getName(), list.size()));
				}
				list.add(index, featureValue);
			} else {
				list.add(featureValue);
			}
		} catch (IllegalArgumentException e) {
			throw new ToolException(String.format("Value is not addable to feature '%s': %s", feature.getName(), e.getMessage()));
		}
	}

	private static void applyRemove(Dataset dataset, EObject eObject, EStructuralFeature feature, Object value, Integer index, ClassifierResolver resolver) {
		if (!feature.isMany()) {
			throw new ToolException(String.format("Feature '%s' is single-valued; use action 'set' with value null or 'unset'", feature.getName()));
		}
		@SuppressWarnings("unchecked")
		EList<Object> list = (EList<Object>) eObject.eGet(feature);
		if (index != null) {
			if (index < 0 || index >= list.size()) {
				throw new ToolException(String.format("Index %d is out of bounds for feature '%s' (size %d)", index, feature.getName(), list.size()));
			}
			list.remove((int) index);
			return;
		}
		if (value == null) {
			throw new ToolException("Action 'remove' requires either an 'index' or a 'value'");
		}
		Object featureValue;
		if (feature instanceof EReference) {
			String refId = String.valueOf(value);
			featureValue = refId.contains(ModelGuard.CLASS_REF_SEPARATOR)
					? resolver.resolveClassifier(refId)
					: dataset.requireObject(refId);
		} else {
			featureValue = coerceAttributeValue((EAttribute) feature, value, Integer.MAX_VALUE);
		}
		if (!list.remove(featureValue)) {
			throw new ToolException(String.format("Value not found in feature '%s'", feature.getName()));
		}
	}

	/**
	 * Converts a raw argument to the value stored in a feature. For a reference,
	 * a class-reference identifier ({@code <nsURI>#//<Name>}, never a
	 * dataset-local {@code o<N>} id) resolves to a registry/built-in classifier
	 * through {@code resolver}; any other string is a dataset-local object id.
	 */
	private static Object toFeatureValue(Dataset dataset, EStructuralFeature feature, Object value, ClassifierResolver resolver, DatasetLimits limits) {
		if (feature instanceof EReference reference) {
			if (!(value instanceof String refId)) {
				throw new ToolException(String.format("Feature '%s' is a reference; pass the objectId of an existing object of this dataset", feature.getName()));
			}
			if (refId.contains(ModelGuard.CLASS_REF_SEPARATOR)) {
				EClassifier target = resolver.resolveClassifier(refId);
				if (!((EClass) reference.getEType()).isInstance(target)) {
					throw new ToolException(String.format("Classifier '%s' (%s) is not compatible with reference '%s' (%s)",
							refId, target.eClass().getName(), reference.getName(), reference.getEType().getName()));
				}
				return target;
			}
			EObject target = dataset.requireObject(refId);
			if (!((EClass) reference.getEType()).isInstance(target)) {
				throw new ToolException(String.format("Object '%s' (%s) is not compatible with reference '%s' (%s)",
						refId, target.eClass().getName(), reference.getName(), reference.getEType().getName()));
			}
			return target;
		}
		return coerceAttributeValue((EAttribute) feature, value, limits.maxValueChars());
	}

	private static Object coerceAttributeValue(EAttribute attribute, Object value, int maxValueChars) {
		EDataType dataType = attribute.getEAttributeType();
		String literal = String.valueOf(value);
		if (literal.length() > maxValueChars) {
			throw new ToolException(String.format("Value for attribute '%s' exceeds the limit of %d characters", attribute.getName(), maxValueChars));
		}
		Class<?> instanceClass = dataType.getInstanceClass();
		if (instanceClass == String.class && value instanceof String
				|| instanceClass != null && !(value instanceof String) && instanceClass.isInstance(value)) {
			return value;
		}
		try {
			Object converted = EcoreUtil.createFromString(dataType, literal);
			if (converted == null) {
				throw new IllegalArgumentException("conversion returned null");
			}
			return converted;
		} catch (Exception e) {
			throw new ToolException(String.format("Cannot convert '%s' to %s for attribute '%s'",
					truncate(literal), dataType.getName(), attribute.getName()));
		}
	}

	private static EStructuralFeature requireFeature(EClass eClass, String featureName) {
		if (featureName == null || featureName.isBlank()) {
			throw new ToolException("Parameter 'feature' must not be empty");
		}
		EStructuralFeature feature = eClass.getEStructuralFeature(featureName);
		if (feature == null) {
			List<String> available = eClass.getEAllStructuralFeatures().stream()
					.filter(f -> f.isChangeable() && !f.isDerived() && !f.isTransient())
					.map(EStructuralFeature::getName)
					.toList();
			throw new ToolException(String.format("EClass '%s' has no feature '%s'. Available features: %s",
					eClass.getName(), featureName, String.join(", ", available)));
		}
		// Derived features are permitted when they are changeable: Ecore's own
		// eType/eSuperTypes are derived-yet-changeable (they delegate to the
		// eGenericType/eGenericSuperTypes), and metamodel authoring must set them.
		if (!feature.isChangeable()) {
			throw new ToolException(String.format("Feature '%s' is not changeable", featureName));
		}
		return feature;
	}

	// Records the reference target of a feature op. The returned string is either
	// a dataset-local object id (o<N>) or a class-reference identifier
	// (<nsURI>#//<Name>); the two are disambiguated on replay by the presence of
	// CLASS_REF_SEPARATOR, which a dataset-local id can never contain.
	private static String refOrNull(EStructuralFeature feature, Object value) {
		return feature instanceof EReference && value instanceof String refId ? refId : null;
	}

	private static void checkObjectCap(Dataset dataset, DatasetLimits limits, int toAdd) {
		if (dataset.objectCount() + toAdd > limits.maxObjectsPerDataset()) {
			throw new ToolException(String.format("Dataset object limit of %d reached", limits.maxObjectsPerDataset()));
		}
	}

	private static void checkRecipeCap(Dataset dataset, DatasetLimits limits) {
		if (dataset.recipeSize() >= limits.maxRecipeOps()) {
			throw new ToolException(String.format("Dataset recipe limit of %d operations reached", limits.maxRecipeOps()));
		}
	}

	private static String truncate(String value) {
		return value.length() <= 80 ? value : value.substring(0, 77) + "...";
	}
}
