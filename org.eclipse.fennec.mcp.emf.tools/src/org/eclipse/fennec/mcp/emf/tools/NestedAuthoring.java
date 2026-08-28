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
package org.eclipse.fennec.mcp.emf.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.fennec.mcp.emf.tools.core.ClassifierResolver;
import org.eclipse.fennec.mcp.emf.tools.core.Dataset;
import org.eclipse.fennec.mcp.emf.tools.core.EcoreAuthoring;
import org.eclipse.fennec.mcp.emf.tools.core.GenericTypes;
import org.eclipse.fennec.mcp.emf.tools.core.PackageLocalResolver;
import org.eclipse.fennec.mcp.emf.tools.core.ToolException;

/**
 * Builds Ecore elements from the argument maps of the authoring tools, so that
 * one element is built the same way whether it arrives as a standalone call
 * ({@code add_eattribute}) or nested inside a composite one
 * ({@code add_eclass} with {@code eAttributes}, {@code create_epackage} with
 * {@code eClassifiers}).
 * <p>
 * A composite call resolves intra-package type references through a
 * {@link PackageLocalResolver} in a second pass, so declaration order does not
 * matter, and registers nothing in the dataset until the whole tree is built —
 * a failure part-way leaves the dataset exactly as it was, and the message
 * names the element that failed by array index and name.
 *
 * @author Mark Hoffmann
 * @since Aug 28, 2026
 */
final class NestedAuthoring {

	private NestedAuthoring() {
	}

	/**
	 * The nested arrays of an EClass spec, in the order they are applied.
	 */
	private static final String E_ANNOTATIONS = "eAnnotations";
	private static final String E_ATTRIBUTES = "eAttributes";
	private static final String E_REFERENCES = "eReferences";

	// ---------------------------------------------------------------- elements

	/**
	 * Wraps a registry resolver so references local to the owner's package
	 * resolve first. Used by the standalone feature tools too, so that
	 * {@code #//<Name>} means the same thing wherever it is written.
	 *
	 * @return the local-first resolver, or {@code delegate} when the owner has no
	 *         package yet
	 */
	static ClassifierResolver localTo(ClassifierResolver delegate, EClass owner) {
		EPackage ePackage = owner.getEPackage();
		return ePackage == null ? delegate : new PackageLocalResolver(delegate, ePackage, List.of(owner));
	}


	/**
	 * Builds the EAttribute described by an argument map — the standalone tool's
	 * own arguments, or one entry of a nested {@code eAttributes} array. The
	 * result is not attached to an owner and not registered in the dataset.
	 */
	static EAttribute buildEAttribute(Dataset dataset, Map<String, Object> spec, ClassifierResolver resolver) {
		EAttribute attribute = EcoreFactory.eINSTANCE.createEAttribute();
		attribute.setName(AbstractEMFTool.requireString(spec, "name"));
		EcoreAuthoring.applyType(attribute, AbstractEMFTool.optionalString(spec, "eType"), spec.get("eGenericType"),
				true, dataset, resolver);
		EcoreAuthoring.applyFlags(attribute, AbstractEMFTool.featureFlags(spec));
		attribute.setID(AbstractEMFTool.optionalBoolean(spec, "iD", false));
		String defaultValueLiteral = AbstractEMFTool.optionalString(spec, "defaultValueLiteral");
		if (defaultValueLiteral != null) {
			attribute.setDefaultValueLiteral(defaultValueLiteral);
		}
		return attribute;
	}

	/**
	 * Builds the EReference described by an argument map. {@code eOpposite} and
	 * {@code eKeys} are dataset objectIds, so they can only point at elements
	 * that exist already — an opposite between two features of the same
	 * composite call is set afterwards with modify_feature.
	 */
	static EReference buildEReference(Dataset dataset, Map<String, Object> spec, ClassifierResolver resolver) {
		EReference reference = EcoreFactory.eINSTANCE.createEReference();
		reference.setName(AbstractEMFTool.requireString(spec, "name"));
		EcoreAuthoring.applyType(reference, AbstractEMFTool.optionalString(spec, "eType"), spec.get("eGenericType"),
				true, dataset, resolver);
		EcoreAuthoring.applyFlags(reference, AbstractEMFTool.featureFlags(spec));
		reference.setContainment(AbstractEMFTool.optionalBoolean(spec, "containment", false));
		reference.setResolveProxies(AbstractEMFTool.optionalBoolean(spec, "resolveProxies", true));
		String oppositeId = AbstractEMFTool.optionalString(spec, "eOpposite");
		if (oppositeId != null) {
			EObject opposite = dataset.requireObject(oppositeId);
			if (!(opposite instanceof EReference oppositeRef)) {
				throw new ToolException(String.format("eOpposite '%s' is a %s, not an EReference", oppositeId,
						opposite.eClass().getName()));
			}
			reference.setEOpposite(oppositeRef);
		}
		for (String keyId : AbstractEMFTool.optionalStringList(spec, "eKeys")) {
			EObject key = dataset.requireObject(keyId);
			if (!(key instanceof EAttribute keyAttribute)) {
				throw new ToolException(String.format("eKey '%s' is a %s, not an EAttribute", keyId,
						key.eClass().getName()));
			}
			reference.getEKeys().add(keyAttribute);
		}
		return reference;
	}

	/**
	 * Builds the EAnnotation described by an argument map. {@code references}
	 * are dataset objectIds of elements that exist already.
	 */
	static EAnnotation buildEAnnotation(Dataset dataset, Map<String, Object> spec) {
		EAnnotation annotation = EcoreFactory.eINSTANCE.createEAnnotation();
		annotation.setSource(AbstractEMFTool.requireString(spec, "source"));
		Object details = spec.get("details");
		if (details != null) {
			if (!(details instanceof Map<?, ?> detailMap)) {
				throw new ToolException("Parameter 'details' must be an object of string key/value pairs");
			}
			for (Map.Entry<?, ?> entry : detailMap.entrySet()) {
				annotation.getDetails().put(String.valueOf(entry.getKey()),
						entry.getValue() == null ? null : String.valueOf(entry.getValue()));
			}
		}
		for (String refId : AbstractEMFTool.optionalStringList(spec, "references")) {
			annotation.getReferences().add(dataset.requireObject(refId));
		}
		return annotation;
	}

	/**
	 * Builds the EEnumLiteral described by an argument map.
	 */
	static EEnumLiteral buildEEnumLiteral(Map<String, Object> spec) {
		Integer value = AbstractEMFTool.optionalInt(spec, "value");
		if (value == null) {
			throw new ToolException("Parameter 'value' is required and must be an integer");
		}
		EEnumLiteral literal = EcoreFactory.eINSTANCE.createEEnumLiteral();
		literal.setName(AbstractEMFTool.requireString(spec, "name"));
		literal.setValue(value);
		String literalString = AbstractEMFTool.optionalString(spec, "literal");
		literal.setLiteral(literalString == null ? literal.getName() : literalString);
		return literal;
	}

	/**
	 * Applies the {@code eGenericSuperTypes} array of an EClass spec.
	 */
	static void applyGenericSuperTypes(Dataset dataset, EClass eClass, Object specValue, ClassifierResolver resolver) {
		if (specValue == null) {
			return;
		}
		if (!(specValue instanceof List<?> list)) {
			throw new ToolException("'eGenericSuperTypes' must be an array of generic types");
		}
		for (Object spec : list) {
			eClass.getEGenericSuperTypes().add(GenericTypes.parse(dataset, resolver, spec));
		}
	}

	// --------------------------------------------------------------- composite

	/**
	 * Applies the nested {@code eAnnotations}, {@code eAttributes} and
	 * {@code eReferences} arrays of an EClass spec. Absent arrays are a no-op,
	 * which is what keeps the standalone call shape working unchanged.
	 */
	static void applyClassChildren(Dataset dataset, EClass eClass, Map<String, Object> spec,
			ClassifierResolver resolver) {
		List<Object> annotations = specList(spec, E_ANNOTATIONS);
		for (int i = 0; i < annotations.size(); i++) {
			Map<String, Object> child = requireSpec(annotations.get(i), context(E_ANNOTATIONS, i, null));
			eClass.getEAnnotations().add(
					inContext(context(E_ANNOTATIONS, i, child.get("source")), () -> buildEAnnotation(dataset, child)));
		}
		List<Object> attributes = specList(spec, E_ATTRIBUTES);
		for (int i = 0; i < attributes.size(); i++) {
			Map<String, Object> child = requireSpec(attributes.get(i), context(E_ATTRIBUTES, i, null));
			eClass.getEStructuralFeatures().add(inContext(context(E_ATTRIBUTES, i, child.get("name")),
					() -> buildEAttribute(dataset, child, resolver)));
		}
		List<Object> references = specList(spec, E_REFERENCES);
		for (int i = 0; i < references.size(); i++) {
			Map<String, Object> child = requireSpec(references.get(i), context(E_REFERENCES, i, null));
			eClass.getEStructuralFeatures().add(inContext(context(E_REFERENCES, i, child.get("name")),
					() -> buildEReference(dataset, child, resolver)));
		}
	}

	/**
	 * Applies the nested {@code eClassifiers} array of a create_epackage call in
	 * two passes: every classifier is created and attached first, then the
	 * things that resolve a type reference (super types, feature types) are
	 * wired through a resolver that can see the package's own classifiers. That
	 * is what makes declaration order irrelevant.
	 */
	static void applyClassifiers(Dataset dataset, EPackage ePackage, Map<String, Object> arguments,
			ClassifierResolver registryResolver) {
		List<Object> specs = specList(arguments, "eClassifiers");
		if (specs.isEmpty()) {
			return;
		}
		List<Map<String, Object>> parsed = new ArrayList<>(specs.size());
		List<EClassifier> created = new ArrayList<>(specs.size());
		for (int i = 0; i < specs.size(); i++) {
			Map<String, Object> spec = requireSpec(specs.get(i), context("eClassifiers", i, null));
			parsed.add(spec);
			created.add(inContext(context("eClassifiers", i, spec.get("name")), () -> createClassifier(spec)));
		}
		// attach before wiring: the local resolver of pass two finds them here
		ePackage.getEClassifiers().addAll(created);
		ClassifierResolver resolver = new PackageLocalResolver(registryResolver, ePackage, List.of());
		for (int i = 0; i < parsed.size(); i++) {
			Map<String, Object> spec = parsed.get(i);
			EClassifier classifier = created.get(i);
			inContext(context("eClassifiers", i, spec.get("name")), () -> {
				if (classifier instanceof EClass eClass) {
					EcoreAuthoring.addSuperTypes(dataset, eClass, AbstractEMFTool.optionalStringList(spec, "eSuperTypes"),
							resolver);
					applyGenericSuperTypes(dataset, eClass, spec.get("eGenericSuperTypes"), resolver);
					applyClassChildren(dataset, eClass, spec, resolver);
				} else {
					applyElementAnnotations(dataset, classifier, spec);
				}
				return null;
			});
		}
	}

	/**
	 * Creates one classifier of an {@code eClassifiers} entry, with the
	 * properties that need no type resolution. Everything referencing another
	 * classifier is wired in the second pass.
	 */
	private static EClassifier createClassifier(Map<String, Object> spec) {
		String kind = AbstractEMFTool.optionalString(spec, "eClass");
		String name = AbstractEMFTool.requireString(spec, "name");
		switch (kind == null ? "EClass" : kind) {
			case "EClass" -> {
				EClass eClass = EcoreFactory.eINSTANCE.createEClass();
				eClass.setName(name);
				boolean isInterface = AbstractEMFTool.optionalBoolean(spec, "interface", false);
				eClass.setInterface(isInterface);
				eClass.setAbstract(isInterface || AbstractEMFTool.optionalBoolean(spec, "abstract", false));
				return eClass;
			}
			case "EEnum" -> {
				EEnum eEnum = EcoreFactory.eINSTANCE.createEEnum();
				eEnum.setName(name);
				List<Object> literals = specList(spec, "eLiterals");
				for (int i = 0; i < literals.size(); i++) {
					Map<String, Object> child = requireSpec(literals.get(i), context("eLiterals", i, null));
					eEnum.getELiterals()
							.add(inContext(context("eLiterals", i, child.get("name")), () -> buildEEnumLiteral(child)));
				}
				return eEnum;
			}
			case "EDataType" -> {
				EDataType eDataType = EcoreFactory.eINSTANCE.createEDataType();
				eDataType.setName(name);
				eDataType.setSerializable(AbstractEMFTool.optionalBoolean(spec, "serializable", true));
				return eDataType;
			}
			default -> throw new ToolException(String.format(
					"'eClass' must be one of EClass, EEnum, EDataType (was '%s')", kind));
		}
	}

	/**
	 * Applies the {@code eAnnotations} of a non-EClass classifier.
	 */
	private static void applyElementAnnotations(Dataset dataset, EModelElement element, Map<String, Object> spec) {
		List<Object> annotations = specList(spec, E_ANNOTATIONS);
		for (int i = 0; i < annotations.size(); i++) {
			Map<String, Object> child = requireSpec(annotations.get(i), context(E_ANNOTATIONS, i, null));
			element.getEAnnotations().add(
					inContext(context(E_ANNOTATIONS, i, child.get("source")), () -> buildEAnnotation(dataset, child)));
		}
	}

	// ------------------------------------------------------------------ result

	/**
	 * Renders the ids a composite call assigned as {@code objectId} / {@code type}
	 * / {@code name} triples, so the agent can address the nested elements
	 * without a follow-up inspect_dataset.
	 */
	static List<Map<String, String>> describe(Map<String, EObject> indexed) {
		List<Map<String, String>> described = new ArrayList<>(indexed.size());
		for (Map.Entry<String, EObject> entry : indexed.entrySet()) {
			EObject eObject = entry.getValue();
			Map<String, String> element = new LinkedHashMap<>(3);
			element.put("objectId", entry.getKey());
			element.put("type", eObject.eClass().getName());
			String name = nameOf(eObject);
			if (name != null) {
				element.put("name", name);
			}
			described.add(element);
		}
		return described;
	}

	private static String nameOf(EObject eObject) {
		if (eObject instanceof org.eclipse.emf.ecore.ENamedElement named) {
			return named.getName();
		}
		return eObject instanceof EAnnotation annotation ? annotation.getSource() : null;
	}

	// ----------------------------------------------------------------- helpers

	/**
	 * @return the entries of a nested array argument, empty when it is absent
	 */
	private static List<Object> specList(Map<String, Object> arguments, String key) {
		Object value = arguments.get(key);
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw new ToolException(String.format("Parameter '%s' must be an array of objects", key));
		}
		return new ArrayList<>(list);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> requireSpec(Object value, String context) {
		if (!(value instanceof Map<?, ?> map)) {
			throw new ToolException(String.format("%s must be an object", context));
		}
		return (Map<String, Object>) map;
	}

	/**
	 * @return the location of a nested element, e.g. {@code eAttributes[1] 'batV'}
	 */
	private static String context(String array, int index, Object name) {
		return name instanceof String label && !label.isBlank()
				? String.format("%s[%d] '%s'", array, index, label)
				: String.format("%s[%d]", array, index);
	}

	/**
	 * Prefixes the message of a {@link ToolException} with the location of the
	 * nested element that produced it. Nesting composes, so a failure deep in a
	 * composite call reads
	 * {@code eClassifiers[1] 'Uplink': eAttributes[0] 'batV': …}.
	 */
	private static <T> T inContext(String context, Supplier<T> body) {
		try {
			return body.get();
		} catch (ToolException e) {
			throw new ToolException(context + ": " + e.getMessage());
		}
	}
}
