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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Security-by-default policy of the session-local {@link PackageRegistry}:
 * which namespace URIs may be registered and how many packages a session may
 * hold. Empty allow-list denies everything.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
@ObjectClassDefinition(name = "EMF Package Registry", description = "Registration policy for session-local, authored/imported EPackages (security-by-default, deny-all).")
public @interface EMFPackageRegistryConfig {

	@AttributeDefinition(name = "nsURI allow-list", description = "Namespace URIs that may be registered. Entries support a trailing '*' wildcard; a single '*' allows all. Empty = deny-all. Reserved namespaces (Ecore, XMLType, GenModel) can never be registered.")
	String[] nsuri_allowlist() default {};

	@AttributeDefinition(name = "nsURI deny-list", description = "Namespace URIs (or trailing-'*' patterns) that must never be registered; overrides the allow-list.")
	String[] nsuri_denylist() default {};

	@AttributeDefinition(name = "Max models per session", description = "Maximum registered packages per session. When exceeded, the least-recently-modified package is evicted.")
	int max_models() default 100;

	@AttributeDefinition(name = "Idle minutes after which a session's registered packages are evicted")
	int session_ttl_minutes() default 120;
}
