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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

/**
 * Session-scoped store of authored/imported {@link EPackage}s. It survives
 * across prompts of the same MCP session so a metamodel can be built
 * incrementally, and — once registered — makes its classifiers resolvable to
 * the instance tools (via {@link ModelGuard}).
 * <p>
 * Registration is <b>security-by-default</b>: a namespace URI is registrable
 * only if it matches the {@code nsuri.allowlist} (empty list denies everything;
 * a single {@code *} allows all; entries may end with {@code *}), is not on the
 * {@code nsuri.denylist} and is not a reserved platform namespace (Ecore,
 * XMLType, GenModel — which can never be shadowed). Each session holds at most
 * {@code max.models} packages; exceeding the cap evicts the
 * least-recently-modified one. A registered package is a frozen
 * {@link EcoreUtil#copy(org.eclipse.emf.ecore.EObject) copy}, so later edits to
 * the authoring dataset never mutate what has already been validated and
 * trusted.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@Component(name = "EMFPackageRegistry", service = PackageRegistry.class, configurationPid = "EMFPackageRegistry")
@Designate(ocd = EMFPackageRegistryConfig.class)
public class PackageRegistry {

	private static final Logger LOGGER = Logger.getLogger(PackageRegistry.class.getName());

	/** Platform namespaces that can never be registered/shadowed. */
	private static final Set<String> RESERVED_NS_URIS = Set.of(
			EcorePackage.eNS_URI, // http://www.eclipse.org/emf/2002/Ecore
			"http://www.eclipse.org/emf/2003/XMLType",
			"http://www.eclipse.org/emf/2002/GenModel");

	private final Map<String, SessionPackages> sessions = new ConcurrentHashMap<>();

	private volatile List<String> allowList = List.of();
	private volatile List<String> denyList = List.of();
	private volatile int maxModels = 100;
	private volatile long sessionTtlMillis = 120 * 60_000L;

	private static final class Registered {
		final EPackage ePackage;
		volatile long lastModified;

		Registered(EPackage ePackage, long lastModified) {
			this.ePackage = ePackage;
			this.lastModified = lastModified;
		}
	}

	private static final class SessionPackages {
		final Map<String, Registered> packages = new LinkedHashMap<>();
		volatile long lastAccess = System.currentTimeMillis();

		void touch() {
			lastAccess = System.currentTimeMillis();
		}
	}

	public PackageRegistry() {
		// default constructor for DS
	}

	/**
	 * Test constructor wiring the policy directly.
	 */
	PackageRegistry(Set<String> nsuriAllowList, Set<String> nsuriDenyList, int maxModels) {
		this.allowList = List.copyOf(nsuriAllowList);
		this.denyList = List.copyOf(nsuriDenyList);
		this.maxModels = maxModels;
	}

	@Activate
	@Modified
	void activate(EMFPackageRegistryConfig config) {
		this.allowList = List.of(config.nsuri_allowlist());
		this.denyList = List.of(config.nsuri_denylist());
		this.maxModels = config.max_models();
		this.sessionTtlMillis = config.session_ttl_minutes() * 60_000L;
		LOGGER.info(() -> String.format("EMF package registry policy: %d allow pattern(s), %d deny pattern(s), max %d model(s) per session",
				allowList.size(), denyList.size(), maxModels));
	}

	/**
	 * Registers a copy of the package for the session under its namespace URI.
	 * Re-registering an already-present namespace replaces it (does not count
	 * against the cap). The package's namespace URI must pass the registration
	 * policy.
	 *
	 * @param sessionId the MCP session id
	 * @param ePackage  the package to register (copied defensively)
	 * @return the registered (copied) package, never {@code null}
	 * @throws ToolException if the namespace URI is denied
	 */
	public EPackage register(String sessionId, EPackage ePackage) {
		if (ePackage == null || ePackage.getNsURI() == null || ePackage.getNsURI().isBlank()) {
			throw new ToolException("Cannot register a package without a namespace URI (nsURI)");
		}
		String nsUri = ePackage.getNsURI();
		requireRegistrable(nsUri);
		EPackage copy = EcoreUtil.copy(ePackage);
		evictExpired();
		SessionPackages store = sessions.computeIfAbsent(requireSession(sessionId), s -> new SessionPackages());
		store.touch();
		synchronized (store) {
			long now = System.currentTimeMillis();
			if (!store.packages.containsKey(nsUri) && store.packages.size() >= maxModels) {
				evictLeastRecentlyModified(store);
			}
			store.packages.put(nsUri, new Registered(copy, now));
		}
		return copy;
	}

	/**
	 * Removes a package from the session.
	 *
	 * @return {@code true} if it existed and was removed
	 */
	public boolean unregister(String sessionId, String nsUri) {
		SessionPackages store = sessions.get(requireSession(sessionId));
		if (store == null) {
			return false;
		}
		store.touch();
		synchronized (store) {
			return store.packages.remove(nsUri) != null;
		}
	}

	/**
	 * Re-keys a registered package to a new namespace URI (e.g. after the
	 * authoring model's nsURI changed). The new URI must pass the policy; the
	 * moved package's own nsURI is updated to match.
	 *
	 * @return the re-keyed package, never {@code null}
	 * @throws ToolException if the old key is unknown or the new URI is denied
	 */
	public EPackage rekey(String sessionId, String oldNsUri, String newNsUri) {
		if (newNsUri == null || newNsUri.isBlank()) {
			throw new ToolException("Parameter 'newNsURI' must not be empty");
		}
		requireRegistrable(newNsUri);
		SessionPackages store = sessions.get(requireSession(sessionId));
		if (store == null) {
			throw new ToolException(String.format("No registered package '%s' in this session", oldNsUri));
		}
		store.touch();
		synchronized (store) {
			Registered registered = store.packages.remove(oldNsUri);
			if (registered == null) {
				throw new ToolException(String.format("No registered package '%s' in this session", oldNsUri));
			}
			registered.ePackage.setNsURI(newNsUri);
			registered.lastModified = System.currentTimeMillis();
			store.packages.put(newNsUri, registered);
			return registered.ePackage;
		}
	}

	/**
	 * Resolves a registered package, touching its last-modified time so an
	 * actively-used package is not evicted.
	 *
	 * @return the package, or {@code null} if not registered in this session
	 */
	public EPackage resolve(String sessionId, String nsUri) {
		SessionPackages store = sessions.get(requireSession(sessionId));
		if (store == null) {
			return null;
		}
		store.touch();
		synchronized (store) {
			Registered registered = store.packages.get(nsUri);
			if (registered == null) {
				return null;
			}
			registered.lastModified = System.currentTimeMillis();
			return registered.ePackage;
		}
	}

	/**
	 * Resolves a classifier of a registered package.
	 *
	 * @return the classifier, or {@code null} if the package or classifier is unknown
	 */
	public EClassifier resolveClassifier(String sessionId, String nsUri, String name) {
		EPackage ePackage = resolve(sessionId, nsUri);
		return ePackage == null ? null : ePackage.getEClassifier(name);
	}

	/**
	 * @return the session's registered packages, sorted by namespace URI
	 */
	public List<EPackage> list(String sessionId) {
		evictExpired();
		SessionPackages store = sessions.get(requireSession(sessionId));
		if (store == null) {
			return List.of();
		}
		store.touch();
		synchronized (store) {
			List<EPackage> result = new ArrayList<>(store.packages.size());
			store.packages.values().forEach(r -> result.add(r.ePackage));
			result.sort(Comparator.comparing(EPackage::getNsURI));
			return result;
		}
	}

	/**
	 * @return {@code true} if the namespace URI would pass the registration policy
	 */
	public boolean isRegistrable(String nsUri) {
		return nsUri != null && !nsUri.isBlank()
				&& !RESERVED_NS_URIS.contains(nsUri)
				&& matches(allowList, nsUri)
				&& !matches(denyList, nsUri);
	}

	private void requireRegistrable(String nsUri) {
		if (RESERVED_NS_URIS.contains(nsUri)) {
			throw new ToolException(String.format("Namespace '%s' is a reserved platform package and cannot be registered", nsUri));
		}
		if (!matches(allowList, nsUri)) {
			throw new ToolException(String.format("Namespace '%s' is not allow-listed for registration. An admin must add it to the EMFPackageRegistry nsuri.allowlist.", nsUri));
		}
		if (matches(denyList, nsUri)) {
			throw new ToolException(String.format("Namespace '%s' is deny-listed for registration", nsUri));
		}
	}

	private static boolean matches(List<String> patterns, String nsUri) {
		for (String pattern : patterns) {
			if ("*".equals(pattern)) {
				return true;
			}
			if (pattern.endsWith("*")) {
				if (nsUri.startsWith(pattern.substring(0, pattern.length() - 1))) {
					return true;
				}
			} else if (pattern.equals(nsUri)) {
				return true;
			}
		}
		return false;
	}

	private static void evictLeastRecentlyModified(SessionPackages store) {
		String victim = null;
		long oldest = Long.MAX_VALUE;
		for (Map.Entry<String, Registered> entry : store.packages.entrySet()) {
			if (entry.getValue().lastModified < oldest) {
				oldest = entry.getValue().lastModified;
				victim = entry.getKey();
			}
		}
		if (victim != null) {
			store.packages.remove(victim);
			String evicted = victim;
			LOGGER.info(() -> String.format("EMF package registry cap reached; evicted least-recently-modified package '%s'", evicted));
		}
	}

	private String requireSession(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new ToolException("No MCP session available. Package registry access requires a session-aware MCP connection.");
		}
		return sessionId;
	}

	private void evictExpired() {
		long now = System.currentTimeMillis();
		sessions.entrySet().removeIf(entry -> now - entry.getValue().lastAccess > sessionTtlMillis);
	}
}
