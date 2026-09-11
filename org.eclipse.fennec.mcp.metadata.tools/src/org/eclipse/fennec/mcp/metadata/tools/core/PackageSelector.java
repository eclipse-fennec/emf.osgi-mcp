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

import java.util.List;
import java.util.StringJoiner;

import org.eclipse.fennec.emf.osgi.metadata.MetadataService;
import org.eclipse.fennec.emf.osgi.model.metadata.ClassMetadata;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;

/**
 * Resolves the {@code nsURI} / {@code fingerprint} pair every read tool in this
 * bundle accepts down to exactly one registered model version.
 * <p>
 * <b>A namespace URI is not an identity.</b> Registration is keyed by model
 * fingerprint, so one nsURI can hold several concurrently registered versions -
 * the same model at two Atlas stages, say. Where that happens an nsURI does not
 * say which one is meant, and this class refuses rather than answering with the
 * most recently registered one: a silently chosen version produces a result that
 * is about a model the caller did not ask for, and nothing in the payload would
 * say so. The refusal lists the fingerprints to choose from, so the next call can
 * be exact.
 * <p>
 * The fingerprint is therefore the precise key and the nsURI the convenient one.
 * Passing both is allowed and checked: a fingerprint belonging to a different
 * namespace is a caller error worth surfacing, not something to silently prefer
 * one way or the other.
 *
 * @author ilenia
 * @since Sep 11, 2026
 */
public final class PackageSelector {

	private PackageSelector() {
		// static helpers
	}

	/**
	 * Resolves exactly one registered model version.
	 *
	 * @param metadata    the metadata service
	 * @param nsURI       the namespace URI, or {@code null} when a fingerprint is given
	 * @param fingerprint the model fingerprint, or {@code null} to address by nsURI
	 * @return the addressed version, never {@code null}
	 * @throws ToolException if neither key is given, nothing matches, the two keys
	 *         disagree, or the nsURI alone holds more than one version
	 */
	public static PackageMetadata require(MetadataService metadata, String nsURI, String fingerprint) {
		if (metadata == null) {
			throw new ToolException("No metadata service is available in this runtime. "
					+ "Call describe_metadata_status for the wiring diagnostics.");
		}
		if (fingerprint != null) {
			PackageMetadata found = metadata.getPackageMetadataByFingerprint(fingerprint)
					.orElseThrow(() -> new ToolException(String.format(
							"No model version with fingerprint '%s' is registered in this runtime. Call "
									+ "describe_metadata_status to list the namespaces and fingerprints it knows.",
							fingerprint)));
			if (nsURI != null && !nsURI.equals(found.getNsURI())) {
				throw new ToolException(String.format(
						"Fingerprint '%s' identifies a model version of namespace '%s', not the '%s' that was "
								+ "also given. Pass one of the two, or a fingerprint belonging to that namespace.",
						fingerprint, found.getNsURI(), nsURI));
			}
			return found;
		}
		if (nsURI == null) {
			throw new ToolException("Either 'nsURI' or 'fingerprint' is required to address a package. "
					+ "Call describe_metadata_status to see what is registered here.");
		}
		List<PackageMetadata> versions = metadata.getPackageMetadataVersions(nsURI);
		if (versions.isEmpty()) {
			throw new ToolException(String.format(
					"No package is registered under namespace '%s'. Call describe_metadata_status to see which "
							+ "namespaces are known to this runtime.", nsURI));
		}
		if (versions.size() > 1) {
			throw new ToolException(ambiguous(nsURI, versions));
		}
		return versions.get(0);
	}

	/**
	 * The optional scope of a runtime-wide query: {@code null} when neither key was
	 * given, so the query stays wide, and otherwise the one version both keys agree
	 * on.
	 *
	 * @param metadata    the metadata service
	 * @param nsURI       the namespace URI, or {@code null}
	 * @param fingerprint the model fingerprint, or {@code null}
	 * @return the version to restrict to, or {@code null} for no restriction
	 * @throws ToolException on the same conditions as {@link #require}, once either
	 *         key is given
	 */
	public static PackageMetadata scope(MetadataService metadata, String nsURI, String fingerprint) {
		return nsURI == null && fingerprint == null ? null : require(metadata, nsURI, fingerprint);
	}

	/**
	 * @param scope         the version a query is restricted to, or {@code null} for
	 *                      no restriction
	 * @param classMetadata a candidate class
	 * @return whether the class belongs to the scoped version - always {@code true}
	 *         when unscoped
	 */
	public static boolean owns(PackageMetadata scope, ClassMetadata classMetadata) {
		if (scope == null) {
			return true;
		}
		if (classMetadata == null) {
			return false;
		}
		PackageMetadata owner = classMetadata.getPackage();
		if (owner == scope) {
			return true;
		}
		String scoped = scope.getModelFingerprint();
		return scoped != null && owner != null && scoped.equals(owner.getModelFingerprint());
	}

	/**
	 * @param classMetadata a class, may be {@code null}
	 * @return the fingerprint of the model version declaring it, or {@code null}
	 */
	public static String fingerprintOf(ClassMetadata classMetadata) {
		PackageMetadata owner = classMetadata == null ? null : classMetadata.getPackage();
		return owner == null ? null : owner.getModelFingerprint();
	}

	/**
	 * The refusal text for an ambiguous namespace. Names every version and what
	 * distinguishes it, because an agent that cannot see the choices can only guess
	 * again.
	 *
	 * @param nsURI    the ambiguous namespace
	 * @param versions its registered versions
	 * @return the message
	 */
	private static String ambiguous(String nsURI, List<PackageMetadata> versions) {
		StringJoiner listed = new StringJoiner("; ");
		for (PackageMetadata version : versions) {
			listed.add(String.format("%s (%s, %d class(es))", version.getModelFingerprint(),
					MetadataViews.origin(version), version.getClasses().size()));
		}
		return String.format(
				"Namespace '%s' holds %d registered model versions, so it does not identify one and this "
						+ "lookup will not guess which was meant. Pass 'fingerprint' to choose one of: %s. "
						+ "Every tool in this bundle that takes an nsURI takes a fingerprint instead.",
				nsURI, versions.size(), listed);
	}
}
