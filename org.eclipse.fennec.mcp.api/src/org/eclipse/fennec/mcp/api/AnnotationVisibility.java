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
package org.eclipse.fennec.mcp.api;

import java.util.Collection;
import java.util.Set;

/**
 * Which EAnnotation sources and metadata aspect types may be shown to an agent.
 * <p>
 * An EAnnotation is where a model keeps the configuration of everything that
 * reads it — codec type mappings, persistence hints, wire names, deployment
 * detail — and an agent that can read annotations reads all of that too. The
 * tools that expose them are spread over two bundles, so the policy lives here,
 * in the one bundle both depend on: a deny-list enforced in only some of the
 * paths that disclose annotations would read as protection while providing none.
 * <p>
 * <b>Why a deny-list, when every other list in these bundles is a deny-all
 * allow-list.</b> Packages and classes are a closed, enumerable set, so naming
 * what is permitted is both possible and safe. Annotation sources are the
 * opposite: open-ended, contributed by whatever bundles happen to be deployed,
 * and the reason the discovery tools exist at all is to find conventions nobody
 * wrote down in advance. An allow-list would hide every unknown-but-harmless
 * source and defeat the feature; a deny-list names the few sources a deployment
 * knows it does not want to hand out.
 * <p>
 * <b>Two lists, because an aspect has no source.</b> A metadata aspect is the
 * <i>parsed</i> form of one or more annotations, and
 * {@code AspectEntry} carries a type id, content and diagnostics but not the
 * annotation source it was built from. Denying an annotation source therefore
 * cannot hide the aspect built from it, and {@code describe_aspects} would hand
 * back the same content — a {@code codec} aspect is exactly a class's
 * serialization configuration. Hence {@link #isAspectTypeVisible(String)} as a
 * separate decision, which a deployment has to keep consistent with the source
 * list itself.
 * <p>
 * Implementations must be thread-safe: tool execution is concurrent.
 *
 * @author ilenia
 * @since Sep 3, 2026
 */
public interface AnnotationVisibility {

	/**
	 * @param annotationSource the {@code EAnnotation.source} URI; a {@code null}
	 *                         source is always visible, since it carries no
	 *                         identity a deny-list could name
	 * @return {@code true} if annotations with this source may be shown
	 */
	boolean isSourceVisible(String annotationSource);

	/**
	 * @param aspectTypeId the metadata aspect type id, e.g. {@code codec}
	 * @return {@code true} if aspects of this type may be shown
	 */
	boolean isAspectTypeVisible(String aspectTypeId);

	/**
	 * @return {@code true} if nothing is denied at all — lets a caller skip the
	 *         filtering work, and lets a result say plainly that no filter applied
	 */
	boolean isUnrestricted();

	/**
	 * The policy denying exactly the given patterns. Both collections speak
	 * {@link UriPatterns}: an exact entry, a {@code prefix*}, or a bare {@code *}.
	 * <p>
	 * Public so that a test, or a caller constructed outside OSGi, exercises the
	 * same matching the deployment uses rather than a stub's idea of it — the
	 * component is nothing but this plus configuration binding.
	 *
	 * @param deniedSources     annotation source patterns to withhold
	 * @param deniedAspectTypes aspect type id patterns to withhold
	 * @return the visibility policy, never {@code null}
	 */
	static AnnotationVisibility denying(Collection<String> deniedSources, Collection<String> deniedAspectTypes) {
		// Copies, so a caller mutating its configuration afterwards cannot widen a
		// live policy. Set.copyOf rather than Set.of: a hand-edited list with a
		// repeated entry must not stop the policy being built at all.
		Set<String> sources = Set.copyOf(deniedSources);
		Set<String> aspectTypes = Set.copyOf(deniedAspectTypes);
		return new AnnotationVisibility() {

			@Override
			public boolean isSourceVisible(String annotationSource) {
				// A null source carries no identity a pattern could name, and the
				// scanners already skip it; treating it as denied would hide anonymous
				// annotations no rule asked to hide.
				return annotationSource == null || !UriPatterns.matches(sources, annotationSource);
			}

			@Override
			public boolean isAspectTypeVisible(String aspectTypeId) {
				return aspectTypeId == null || !UriPatterns.matches(aspectTypes, aspectTypeId);
			}

			@Override
			public boolean isUnrestricted() {
				return sources.isEmpty() && aspectTypes.isEmpty();
			}
		};
	}

	/**
	 * An implementation denying nothing, for tests and for callers constructed
	 * outside OSGi. The deployed policy always comes from the
	 * {@code MCPAnnotationVisibility} configuration.
	 *
	 * @return a permit-all visibility
	 */
	static AnnotationVisibility unrestricted() {
		return denying(Set.of(), Set.of());
	}
}
