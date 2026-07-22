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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

/**
 * Central allow-list enforcement for the EMF model MCP tools
 * (security-by-default, deny-all).
 * <p>
 * An {@link EClass} is usable only if its {@link EPackage} namespace URI is on
 * the package allow-list <b>and</b> the class identifier
 * ({@code <nsURI>#//<ClassName>}) is on the class allow-list. Empty lists deny
 * everything. The allow-lists are admin-owned configuration; allow-listing a
 * package implies trusting its generated factory and datatype conversion code,
 * which runs in-process.
 * <p>
 * EClass resolution happens exclusively against the OSGi EPackage registry —
 * never by dereferencing agent-supplied URIs through a {@link ResourceSet}
 * (no on-demand loading, no external schemes), preventing SSRF and file reads.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "EMFModelGuard", service = ModelGuard.class, configurationPid = "EMFModelGuard", configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = ModelGuardConfig.class)
public class ModelGuard {

	private static final Logger LOGGER = Logger.getLogger(ModelGuard.class.getName());
	/** Separator between the package namespace URI and the class name in a class identifier. */
	public static final String CLASS_REF_SEPARATOR = "#//";

	@Reference
	private ResourceSetFactory resourceSetFactory;

	private volatile Set<String> packageAllowList = Set.of();
	private volatile Set<String> classAllowList = Set.of();
	private volatile EPackage.Registry packageRegistry;

	public ModelGuard() {
		// default constructor for DS
	}

	/**
	 * Test constructor wiring the registry and allow-lists directly.
	 */
	ModelGuard(EPackage.Registry packageRegistry, Set<String> packageAllowList, Set<String> classAllowList) {
		this.packageRegistry = packageRegistry;
		this.packageAllowList = Set.copyOf(packageAllowList);
		this.classAllowList = Set.copyOf(classAllowList);
	}

	@Activate
	void activate(ModelGuardConfig config) {
		this.packageRegistry = resourceSetFactory.createResourceSet().getPackageRegistry();
		update(config);
	}

	@Modified
	void update(ModelGuardConfig config) {
		this.packageAllowList = Set.of(config.epackage_allowlist());
		this.classAllowList = Set.of(config.eclass_allowlist());
		LOGGER.info(() -> String.format("EMF model guard updated: %d allow-listed EPackage(s), %d allow-listed EClass(es)",
				packageAllowList.size(), classAllowList.size()));
	}

	/**
	 * Creates a fresh {@link ResourceSet} backed by the OSGi EPackage registry.
	 * @return a new resource set, never {@code null}
	 */
	public ResourceSet createResourceSet() {
		return resourceSetFactory.createResourceSet();
	}

	/**
	 * Returns the allow-listed packages that are actually resolvable in the
	 * registry, sorted by namespace URI for deterministic output.
	 * @return the allowed packages, never {@code null}
	 */
	public List<EPackage> allowedPackages() {
		List<EPackage> result = new ArrayList<>();
		for (String nsUri : packageAllowList) {
			EPackage ePackage = resolvePackage(nsUri);
			if (ePackage != null) {
				result.add(ePackage);
			}
		}
		result.sort(Comparator.comparing(EPackage::getNsURI));
		return result;
	}

	/**
	 * Resolves an allow-listed package by namespace URI.
	 * @param nsUri the package namespace URI
	 * @return the package, never {@code null}
	 * @throws ToolException if the package is not allow-listed or not registered
	 */
	public EPackage requireAllowedPackage(String nsUri) {
		if (nsUri == null || nsUri.isBlank()) {
			throw new ToolException("Parameter 'nsURI' must not be empty");
		}
		// Check the allow-list before touching the registry, so denied input never probes anything
		if (!packageAllowList.contains(nsUri)) {
			throw new ToolException(String.format("EPackage '%s' is not allow-listed. Use list_metamodel to see the available packages.", nsUri));
		}
		EPackage ePackage = resolvePackage(nsUri);
		if (ePackage == null) {
			throw new ToolException(String.format("EPackage '%s' is allow-listed but not registered in this runtime", nsUri));
		}
		return ePackage;
	}

	/**
	 * Resolves an allow-listed, concrete EClass from its identifier
	 * ({@code <nsURI>#//<ClassName>}). Resolution goes through the package
	 * registry only — agent-supplied URIs are never dereferenced.
	 *
	 * @param eClassRef the class identifier
	 * @return the resolved EClass, never {@code null}
	 * @throws ToolException if the reference is malformed, denied, unknown, or not a concrete EClass
	 */
	public EClass requireAllowedEClass(String eClassRef) {
		if (eClassRef == null || !eClassRef.contains(CLASS_REF_SEPARATOR)) {
			throw new ToolException("Parameter 'eClass' must have the form <nsURI>#//<ClassName>");
		}
		int separator = eClassRef.indexOf(CLASS_REF_SEPARATOR);
		String nsUri = eClassRef.substring(0, separator);
		String className = eClassRef.substring(separator + CLASS_REF_SEPARATOR.length());
		EPackage ePackage = requireAllowedPackage(nsUri);
		EClassifier classifier = ePackage.getEClassifier(className);
		if (!(classifier instanceof EClass eClass)) {
			throw new ToolException(String.format("'%s' is not an EClass of package '%s'. Use list_metamodel to see the available classes.", className, nsUri));
		}
		// allow-list check first: a denied class must not leak its existence or shape
		if (!isClassAllowed(eClass)) {
			throw new ToolException(String.format("EClass '%s' is not allow-listed. Use list_metamodel to see the available classes.", eClassRef));
		}
		if (eClass.isAbstract() || eClass.isInterface()) {
			throw new ToolException(String.format("EClass '%s' is abstract and cannot be instantiated", className));
		}
		return eClass;
	}

	/**
	 * Resolves an allowed {@link EClassifier} from its identifier
	 * ({@code <nsURI>#//<Name>}) for use as a <i>type</i> reference (e.g.
	 * {@code eType}, {@code eSuperTypes}, {@code eOpposite}). Unlike
	 * {@link #requireAllowedEClass(String)} it does <b>not</b> reject abstract
	 * classes, interfaces or non-EClass classifiers (EDataType/EEnum) and does
	 * not enforce the class allow-list — typing is not instantiation.
	 * <p>
	 * The built-in Ecore datatypes ({@code EString}, {@code EInt}, …) are always
	 * resolvable (they are the canonical {@link EcorePackage} constants, not a
	 * dereferenced URI); every other package must be allow-listed.
	 *
	 * @param classifierRef the classifier identifier
	 * @return the resolved classifier, never {@code null}
	 * @throws ToolException if the reference is malformed, denied or unknown
	 */
	public EClassifier requireAllowedClassifier(String classifierRef) {
		if (classifierRef == null || !classifierRef.contains(CLASS_REF_SEPARATOR)) {
			throw new ToolException("Classifier reference must have the form <nsURI>#//<Name>");
		}
		int separator = classifierRef.indexOf(CLASS_REF_SEPARATOR);
		String nsUri = classifierRef.substring(0, separator);
		String name = classifierRef.substring(separator + CLASS_REF_SEPARATOR.length());
		EPackage ePackage = EcorePackage.eNS_URI.equals(nsUri) ? EcorePackage.eINSTANCE : requireAllowedPackage(nsUri);
		EClassifier classifier = ePackage.getEClassifier(name);
		if (classifier == null) {
			throw new ToolException(String.format("'%s' is not a classifier of package '%s'. Use list_metamodel to see the available classifiers.", name, nsUri));
		}
		return classifier;
	}

	/**
	 * Produces a {@link ClassifierResolver} for a single tool call. The resolver
	 * captures this guard's resolution context so the reflective operations can
	 * resolve {@code #//} references without knowing about the guard, the OSGi
	 * registry or the session.
	 *
	 * @param sessionId the calling MCP session (used by the session-local
	 *                  package registry; ignored while none is wired)
	 * @return a resolver, never {@code null}
	 */
	public ClassifierResolver resolverFor(String sessionId) {
		return new ClassifierResolver() {
			@Override
			public EClassifier resolveClassifier(String classifierRef) {
				return requireAllowedClassifier(classifierRef);
			}

			@Override
			public EClass resolveConcreteEClass(String eClassRef) {
				return requireAllowedEClass(eClassRef);
			}
		};
	}

	/**
	 * @param eClass the class to check
	 * @return {@code true} if both the class and its package are allow-listed
	 */
	public boolean isClassAllowed(EClass eClass) {
		EPackage ePackage = eClass.getEPackage();
		return ePackage != null
				&& packageAllowList.contains(ePackage.getNsURI())
				&& classAllowList.contains(refOf(eClass));
	}

	/**
	 * Returns the allow-listed concrete classes of a package, sorted by name.
	 * @param ePackage the package
	 * @return the allowed classes, never {@code null}
	 */
	public List<EClass> allowedConcreteClasses(EPackage ePackage) {
		return ePackage.getEClassifiers().stream()
				.filter(EClass.class::isInstance)
				.map(EClass.class::cast)
				.filter(c -> !c.isAbstract() && !c.isInterface())
				.filter(this::isClassAllowed)
				.sorted(Comparator.comparing(EClass::getName))
				.toList();
	}

	/**
	 * @param eClass the class
	 * @return the class identifier of the form {@code <nsURI>#//<ClassName>}
	 */
	public static String refOf(EClass eClass) {
		return eClass.getEPackage().getNsURI() + CLASS_REF_SEPARATOR + eClass.getName();
	}

	private EPackage resolvePackage(String nsUri) {
		try {
			return packageRegistry.getEPackage(nsUri);
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, e, () -> String.format("Failed to resolve EPackage '%s' from the registry", nsUri));
			return null;
		}
	}
}
