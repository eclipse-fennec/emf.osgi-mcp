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
package org.eclipse.fennec.mcp.gogo.runtime.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.fennec.mcp.gogo.runtime.GogoMCPConstants;
import org.osgi.annotation.bundle.Requirement;
import org.osgi.namespace.implementation.ImplementationNamespace;

/**
 * Bundle requirement annotation that declares a dependency on the Gogo MCP
 * integration. Apply to components or packages that need the Gogo shell
 * MCP server to be active at runtime.
 *
 * @author mark
 * @since 05.04.2026
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({
		ElementType.TYPE, ElementType.PACKAGE
})
@Requirement(namespace = ImplementationNamespace.IMPLEMENTATION_NAMESPACE, name = GogoMCPConstants.MCP_GOGO_IMPLEMENTATION, version = GogoMCPConstants.MCP_GOGO_VERSION)
public @interface RequireGogoMCP {

}
