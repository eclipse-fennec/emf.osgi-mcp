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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EParameter;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.fennec.emf.osgi.metadata.MetadataIndexReader;
import org.eclipse.fennec.emf.osgi.metadata.MetadataService;
import org.eclipse.fennec.emf.osgi.model.metadata.ClassMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.FeatureMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.MetadataRegistry;
import org.eclipse.fennec.emf.osgi.model.metadata.OperationMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;

/**
 * Renders metadata-layer elements into the identity-and-structure result shape
 * the metadata MCP tools return: an {@code <nsURI>#//<Name>} reference plus the
 * few flags an agent needs to decide what to do next. Never a serialized model —
 * reading a model in full is what {@code describe_eclass} and the (separate)
 * {@code export_package} are for.
 * <p>
 * Everything here sorts by nsURI then name and de-duplicates on the rendered
 * reference. The index keeps every registered <em>version</em> of a package, so
 * a raw query answers the same {@code <nsURI>#//<Name>} once per version;
 * {@code describe_package_metadata} is the tool that shows versions apart.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
public final class MetadataViews {

	/** Origin of a package that arrived as an OSGi {@code EPackage} service. */
	public static final String ORIGIN_OSGI = "osgi-service";

	/** Origin of a package announced by an MCP session (import_ecore / register_package). */
	public static final String ORIGIN_SESSION = "session";

	/**
	 * Service property every OSGi service registration carries. Packages announced by
	 * {@code PackageRegistry} go through {@code registerPackage(ePackage)} without
	 * properties, so its presence is the cheap discriminator between the two populations.
	 */
	private static final String SERVICE_ID = "service.id";

	private MetadataViews() {
		// static helpers
	}

	/**
	 * @param metadata the metadata service
	 * @return the bound index reader
	 * @throws ToolException if no index is bound, so an empty answer is never confused
	 *         with an unavailable index
	 */
	public static MetadataIndexReader requireIndex(MetadataService metadata) {
		if (metadata == null) {
			throw new ToolException("No metadata service is available in this runtime. "
					+ "Call describe_metadata_status for the wiring diagnostics.");
		}
		return metadata.getIndexReader().orElseThrow(() -> new ToolException(
				"No metadata index is available in this runtime, so no lookup can be answered. "
						+ "An empty result here would be indistinguishable from 'nothing matched'. "
						+ "Call describe_metadata_status for the wiring diagnostics."));
	}

	/**
	 * @param metadata the metadata service
	 * @return every registered package version, never {@code null}
	 */
	public static List<PackageMetadata> packages(MetadataService metadata) {
		if (metadata == null) {
			return List.of();
		}
		MetadataRegistry registry = metadata.getRegistry();
		return registry == null ? List.of() : List.copyOf(registry.getPackages());
	}

	/**
	 * @param packageMetadata a registered package
	 * @return {@link #ORIGIN_OSGI} or {@link #ORIGIN_SESSION}
	 */
	public static String origin(PackageMetadata packageMetadata) {
		if (packageMetadata == null) {
			return ORIGIN_SESSION;
		}
		return packageMetadata.getProperties().containsKey(SERVICE_ID) ? ORIGIN_OSGI : ORIGIN_SESSION;
	}

	public static String nsURIOf(ClassMetadata classMetadata) {
		PackageMetadata owner = classMetadata == null ? null : classMetadata.getPackage();
		if (owner != null && owner.getNsURI() != null) {
			return owner.getNsURI();
		}
		EClass eClass = classMetadata == null ? null : classMetadata.getEClass();
		EPackage ePackage = eClass == null ? null : eClass.getEPackage();
		return ePackage == null ? null : ePackage.getNsURI();
	}

	public static String classReference(ClassMetadata classMetadata) {
		String nsURI = nsURIOf(classMetadata);
		String name = classMetadata == null ? null : classMetadata.getName();
		return nsURI == null || name == null ? null : nsURI + "#//" + name;
	}

	/**
	 * @param eClassifier a classifier
	 * @return its {@code <nsURI>#//<Name>} reference, or its bare name when the
	 *         classifier is not in a package
	 */
	public static String typeReference(EClassifier eClassifier) {
		if (eClassifier == null) {
			return null;
		}
		EPackage ePackage = eClassifier.getEPackage();
		return ePackage == null ? eClassifier.getName() : ePackage.getNsURI() + "#//" + eClassifier.getName();
	}

	/**
	 * Renders a class hit. Supertypes are returned as full references on purpose:
	 * {@code describe_eclass} reports them as bare names, from which an agent cannot
	 * build the {@code eSuperTypes} argument that {@code add_eclass} expects.
	 *
	 * @param classMetadata the matched class
	 * @return the rendered hit
	 */
	public static Map<String, Object> classHit(ClassMetadata classMetadata) {
		EClass eClass = classMetadata.getEClass();
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("reference", classReference(classMetadata));
		hit.put("nsURI", nsURIOf(classMetadata));
		hit.put("name", classMetadata.getName());
		hit.put("abstract", eClass != null && eClass.isAbstract());
		hit.put("interface", eClass != null && eClass.isInterface());
		hit.put("origin", origin(classMetadata.getPackage()));
		if (eClass != null) {
			hit.put("eSuperTypes", eClass.getESuperTypes().stream()
					.map(MetadataViews::typeReference)
					.filter(Objects::nonNull)
					.toList());
		}
		return hit;
	}

	/**
	 * @param featureMetadata the matched feature
	 * @return the rendered hit
	 */
	public static Map<String, Object> featureHit(FeatureMetadata featureMetadata) {
		ClassMetadata owner = featureMetadata.getClassMetadata();
		EStructuralFeature feature = featureMetadata.getEFeature();
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("reference", featureReference(featureMetadata));
		hit.put("nsURI", nsURIOf(owner));
		hit.put("className", owner == null ? null : owner.getName());
		hit.put("classReference", classReference(owner));
		hit.put("name", featureMetadata.getName());
		hit.put("kind", featureKind(feature));
		if (feature != null) {
			hit.put("type", typeReference(feature.getEType()));
			hit.put("many", feature.isMany());
			hit.put("required", feature.isRequired());
		}
		if (featureMetadata.getExtendedMetaDataName() != null) {
			hit.put("extendedMetaDataName", featureMetadata.getExtendedMetaDataName());
		}
		hit.put("origin", origin(owner == null ? null : owner.getPackage()));
		return hit;
	}

	/**
	 * @param operationMetadata the matched operation
	 * @return the rendered hit
	 */
	public static Map<String, Object> operationHit(OperationMetadata operationMetadata) {
		ClassMetadata owner = operationMetadata.getClassMetadata();
		EOperation operation = operationMetadata.getEOperation();
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("reference", operationReference(operationMetadata));
		hit.put("nsURI", nsURIOf(owner));
		hit.put("className", owner == null ? null : owner.getName());
		hit.put("classReference", classReference(owner));
		hit.put("name", operationMetadata.getName());
		if (operation != null) {
			hit.put("returnType", typeReference(operation.getEType()));
			List<Map<String, Object>> parameters = new ArrayList<>();
			for (EParameter parameter : operation.getEParameters()) {
				Map<String, Object> rendered = new LinkedHashMap<>();
				rendered.put("name", parameter.getName());
				rendered.put("type", typeReference(parameter.getEType()));
				parameters.add(rendered);
			}
			hit.put("parameters", parameters);
		}
		hit.put("origin", origin(owner == null ? null : owner.getPackage()));
		return hit;
	}

	public static String featureReference(FeatureMetadata featureMetadata) {
		return memberReference(featureMetadata.getClassMetadata(), featureMetadata.getName());
	}

	public static String operationReference(OperationMetadata operationMetadata) {
		return memberReference(operationMetadata.getClassMetadata(), operationMetadata.getName());
	}

	private static String memberReference(ClassMetadata owner, String memberName) {
		String classReference = classReference(owner);
		return classReference == null || memberName == null ? null : classReference + "/" + memberName;
	}

	private static String featureKind(EStructuralFeature feature) {
		if (feature instanceof EAttribute) {
			return "attribute";
		}
		if (feature instanceof EReference reference) {
			return reference.isContainment() ? "containment" : "reference";
		}
		return "feature";
	}

	/**
	 * Sorts by nsURI then name and de-duplicates on the rendered reference.
	 *
	 * @param <T>      the metadata element type
	 * @param elements the raw index answers
	 * @param renderer renders one element
	 * @return the stable, de-duplicated hits
	 */
	public static <T> List<Map<String, Object>> hits(List<T> elements,
			Function<T, Map<String, Object>> renderer) {
		List<Map<String, Object>> rendered = new ArrayList<>(elements.size());
		for (T element : elements) {
			rendered.add(renderer.apply(element));
		}
		rendered.sort(Comparator
				.comparing((Map<String, Object> hit) -> string(hit, "nsURI"), Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(hit -> string(hit, "className"), Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(hit -> string(hit, "name"), Comparator.nullsLast(Comparator.naturalOrder())));
		Set<String> seen = new LinkedHashSet<>();
		List<Map<String, Object>> unique = new ArrayList<>(rendered.size());
		for (Map<String, Object> hit : rendered) {
			String reference = string(hit, "reference");
			if (reference == null || seen.add(reference)) {
				unique.add(hit);
			}
		}
		return unique;
	}

	/**
	 * Echoes back the annotation that made an element match. With the {@code value}
	 * argument omitted the query is a wildcard over the key, so the value each hit
	 * actually carries is the part the agent came for — a wildcard query has to be
	 * self-explanatory in its own result.
	 *
	 * @param element the matched model element, may be {@code null}
	 * @param source  the queried annotation source
	 * @param key     the queried detail key
	 * @return the source, key and the value found on this element
	 */
	public static Map<String, Object> matchedAnnotation(EModelElement element, String source, String key) {
		Map<String, Object> matched = new LinkedHashMap<>();
		matched.put("annotationSource", source);
		matched.put("key", key);
		EAnnotation annotation = element == null ? null : element.getEAnnotation(source);
		matched.put("value", annotation == null ? null : annotation.getDetails().get(key));
		return matched;
	}

	private static String string(Map<String, Object> hit, String key) {
		Object value = hit.get(key);
		return value instanceof String text ? text : null;
	}
}
