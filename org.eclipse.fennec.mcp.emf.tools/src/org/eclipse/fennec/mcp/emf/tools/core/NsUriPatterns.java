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

/**
 * The one pattern language every allow- and deny-list in this bundle speaks:
 * an exact string, a {@code prefix*}, or a bare {@code *}.
 * <p>
 * Deliberately not a glob and not a regex. A list entry is either a literal or a
 * prefix, so an entry can only ever widen a rule <em>rightwards</em> from a
 * namespace root the admin wrote out in full — which is what makes a prefix rule
 * readable as a statement about a namespace. Prefix matching is anchored on the
 * whole string, never a substring search, so a rule for
 * {@code https://eclipse.org/fennec/} cannot be satisfied by
 * {@code https://evil.example/https://eclipse.org/fennec/x}.
 * <p>
 * An empty collection matches nothing. Every list in this bundle is deny-all
 * when unconfigured, and that follows from this.
 *
 * @author ilenia
 * @since Aug 27, 2026
 */
final class NsUriPatterns {

	/** The wildcard suffix that turns an entry into a prefix rule, and alone into "everything". */
	static final String WILDCARD = "*";

	private NsUriPatterns() {
		// static helpers
	}

	/**
	 * @param patterns the configured patterns; an empty collection matches nothing
	 * @param value    the namespace URI or class identifier to test
	 * @return {@code true} if some pattern admits the value
	 */
	static boolean matches(Collection<String> patterns, String value) {
		if (value == null || patterns == null) {
			return false;
		}
		for (String pattern : patterns) {
			if (pattern == null || pattern.isBlank()) {
				// A blank entry is a configuration slip, not a wildcard. Treating it as
				// one would turn a stray comma in the config into deny-all becoming
				// allow-all.
				continue;
			}
			if (WILDCARD.equals(pattern)) {
				return true;
			}
			if (pattern.endsWith(WILDCARD)) {
				if (value.startsWith(pattern.substring(0, pattern.length() - WILDCARD.length()))) {
					return true;
				}
			} else if (pattern.equals(value)) {
				return true;
			}
		}
		return false;
	}
}
