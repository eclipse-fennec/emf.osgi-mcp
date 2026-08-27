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
import java.util.LinkedHashSet;
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
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

/**
 * Central allow-list enforcement for the EMF model MCP tools
 * (security-by-default, deny-all).
 * <p>
 * An {@link EClass} is usable only if its {@link EPackage} namespace URI is
 * admitted by the package allow-list <b>and</b> the class identifier
 * ({@code <nsURI>#//<ClassName>}) is admitted by the class allow-list. Empty
 * lists deny everything. The allow-lists are admin-owned configuration;
 * allow-listing a package implies trusting its generated factory and datatype
 * conversion code, which runs in-process.
 * <p>
 * Both lists speak {@link NsUriPatterns}: an exact entry, a {@code prefix*}, or a
 * bare {@code *}. The two stay <b>independent</b> — a package pattern says what
 * may be seen, never what may be instantiated, so widening the package list does
 * not expose a single class. An empty class list is still deny-all however wide
 * the package list is.
 * <p>
 * Patterns exist because a registry is not always fully known when the
 * configuration is written: packages mirrored from a model.atlas scope arrive
 * after startup, and before this the metadata discovery tools could find such a
 * package while {@code list_metamodel} and {@code describe_eclass} could not read
 * it — visible but unreadable, and only a human could resolve it.
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

	/** Session-local authored/imported packages; optional (may be absent in tests or minimal runtimes). */
	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
	private volatile PackageRegistry sessionPackages;

	private volatile Set<String> packageAllowList = Set.of();
	private volatile Set<String> classAllowList = Set.of();
	private volatile EPackage.Registry packageRegistry;
	private volatile Runnable policyChangeListener;

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

	/**
	 * Test constructor additionally wiring the session-local package registry.
	 */
	ModelGuard(EPackage.Registry packageRegistry, PackageRegistry sessionPackages, Set<String> packageAllowList, Set<String> classAllowList) {
		this(packageRegistry, packageAllowList, classAllowList);
		this.sessionPackages = sessionPackages;
	}

	@Activate
	void activate(ModelGuardConfig config) {
		this.packageRegistry = resourceSetFactory.createResourceSet().getPackageRegistry();
		update(config);
	}

	@Modified
	void update(ModelGuardConfig config) {
		// Set.copyOf, not Set.of: Set.of throws on a duplicate entry, and a list
		// someone hand-edits should tolerate a repeated line rather than refusing to
		// activate the guard at all.
		this.packageAllowList = Set.copyOf(List.of(config.epackage_allowlist()));
		this.classAllowList = Set.copyOf(List.of(config.eclass_allowlist()));
		LOGGER.info(() -> String.format("EMF model guard updated: %d allow-listed EPackage(s), %d allow-listed EClass(es)",
				packageAllowList.size(), classAllowList.size()));
		Runnable listener = policyChangeListener;
		if (listener != null) {
			listener.run();
		}
	}

	/**
	 * Sets the single policy change listener, notified on configuration
	 * updates. Used by the runtime introspection service to bump its
	 * {@code service.changecount}.
	 */
	public void onPolicyChange(Runnable listener) {
		this.policyChangeListener = listener;
	}

	/**
	 * @return the configured EPackage allow-list, sorted
	 */
	public String[] packageAllowListSnapshot() {
		return packageAllowList.stream().sorted().toArray(String[]::new);
	}

	/**
	 * @return the configured EClass allow-list, sorted
	 */
	public String[] classAllowListSnapshot() {
		return classAllowList.stream().sorted().toArray(String[]::new);
	}

	/**
	 * Creates a fresh {@link ResourceSet} backed by the OSGi EPackage registry.
	 * @return a new resource set, never {@code null}
	 */
	public ResourceSet createResourceSet() {
		return resourceSetFactory.createResourceSet();
	}

	/**
	 * Returns the registered packages the allow-list admits, sorted by namespace
	 * URI for deterministic output.
	 * <p>
	 * This filters the <b>registry</b> rather than resolving the allow-list's
	 * entries, and that direction is the point: with a {@code prefix*} or {@code *}
	 * rule the set of admitted namespaces is not enumerable from the configuration
	 * at all. It is also what lets a package that arrives <em>after</em> startup —
	 * one mirrored from a model.atlas scope, say — be listed and read without
	 * anyone editing configuration to name it.
	 * <p>
	 * A namespace whose descriptor fails to resolve is skipped, not fatal: one bad
	 * entry in a large registry must not blank out {@code list_metamodel}.
	 *
	 * @return the allowed packages, never {@code null}
	 */
	public List<EPackage> allowedPackages() {
		List<EPackage> result = new ArrayList<>();
		for (String nsUri : candidateNamespaces()) {
			if (!NsUriPatterns.matches(packageAllowList, nsUri)) {
				continue;
			}
			EPackage ePackage = resolvePackage(nsUri);
			if (ePackage != null) {
				result.add(ePackage);
			}
		}
		result.sort(Comparator.comparing(EPackage::getNsURI));
		return result;
	}

	/**
	 * The namespaces worth resolving: what the registry enumerates, plus the
	 * allow-list's own literal entries.
	 * <p>
	 * The second half is not redundant. Enumerating a registry is only as complete
	 * as its {@code keySet()}: a registry that delegates without overriding it
	 * reports its local entries only, and a purely enumerative implementation would
	 * then quietly return nothing for a configuration that used to work. Adding the
	 * literals back makes "exact entries behave exactly as before" true by
	 * construction — only {@code prefix*} and {@code *} depend on the registry being
	 * enumerable, and those cannot be satisfied any other way.
	 * <p>
	 * Reading the registry is guarded: it is live, and a whiteboard change
	 * concurrent with a tool call must not surface as a failed call.
	 */
	private Set<String> candidateNamespaces() {
		Set<String> candidates = new LinkedHashSet<>();
		try {
			candidates.addAll(packageRegistry.keySet());
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, e, () -> "Failed to enumerate the EPackage registry");
		}
		for (String pattern : packageAllowList) {
			if (pattern != null && !pattern.isBlank() && !pattern.endsWith(NsUriPatterns.WILDCARD)) {
				candidates.add(pattern);
			}
		}
		return candidates;
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
		if (!NsUriPatterns.matches(packageAllowList, nsUri)) {
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
	 * @param sessionId the calling MCP session; its session-local registered
	 *                  packages are consulted first and, being already
	 *                  policy-checked and validated, resolve without an
	 *                  allow-list entry (they also shadow an OSGi package of the
	 *                  same nsURI)
	 * @return a resolver, never {@code null}
	 */
	public ClassifierResolver resolverFor(String sessionId) {
		return new ClassifierResolver() {
			@Override
			public EClassifier resolveClassifier(String classifierRef) {
				EClassifier local = resolveSessionLocal(sessionId, classifierRef);
				return local != null ? local : requireAllowedClassifier(classifierRef);
			}

			@Override
			public EClass resolveConcreteEClass(String eClassRef) {
				EClassifier local = resolveSessionLocal(sessionId, eClassRef);
				return local != null ? requireConcrete(local, eClassRef) : requireAllowedEClass(eClassRef);
			}
		};
	}

	/**
	 * Resolves a classifier from a session-local registered package, or
	 * {@code null} when there is no such package (so the caller falls back to
	 * the OSGi allow-list). A session-local hit is trusted — it already passed
	 * the registration policy and validation.
	 */
	private EClassifier resolveSessionLocal(String sessionId, String classifierRef) {
		if (sessionPackages == null || sessionId == null || classifierRef == null || !classifierRef.contains(CLASS_REF_SEPARATOR)) {
			return null;
		}
		int separator = classifierRef.indexOf(CLASS_REF_SEPARATOR);
		String nsUri = classifierRef.substring(0, separator);
		String name = classifierRef.substring(separator + CLASS_REF_SEPARATOR.length());
		return sessionPackages.resolveClassifier(sessionId, nsUri, name);
	}

	private static EClass requireConcrete(EClassifier classifier, String ref) {
		if (!(classifier instanceof EClass eClass)) {
			throw new ToolException(String.format("'%s' is not an EClass and cannot be instantiated", ref));
		}
		if (eClass.isAbstract() || eClass.isInterface()) {
			throw new ToolException(String.format("EClass '%s' is abstract and cannot be instantiated", eClass.getName()));
		}
		return eClass;
	}

	/**
	 * @param eClass the class to check
	 * @return {@code true} if both the class and its package are allow-listed
	 */
	public boolean isClassAllowed(EClass eClass) {
		EPackage ePackage = eClass.getEPackage();
		return ePackage != null
				&& NsUriPatterns.matches(packageAllowList, ePackage.getNsURI())
				&& NsUriPatterns.matches(classAllowList, refOf(eClass));
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
