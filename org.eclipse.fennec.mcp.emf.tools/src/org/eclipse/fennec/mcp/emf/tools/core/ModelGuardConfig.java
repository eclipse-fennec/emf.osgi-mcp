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
 * Configuration for the {@link ModelGuard}. Both allow-lists default to empty,
 * which means <b>deny-all</b>: no EPackage is visible and no EClass is
 * instantiable unless explicitly listed (security-by-default).
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@ObjectClassDefinition(name = "EMF Model Guard", description = "Allow-lists for the EMF model MCP tools. Empty lists deny everything (security-by-default).")
public @interface ModelGuardConfig {

	@AttributeDefinition(name = "EPackage allow-list", description = "Namespace URIs of EPackages exposed to the MCP tools. Empty = deny-all.")
	String[] epackage_allowlist() default {};

	@AttributeDefinition(name = "EClass allow-list", description = "EClass identifiers of the form <nsURI>#//<ClassName> that may be instantiated. A class is only usable if its package is also allow-listed. Empty = deny-all.")
	String[] eclass_allowlist() default {};
}
