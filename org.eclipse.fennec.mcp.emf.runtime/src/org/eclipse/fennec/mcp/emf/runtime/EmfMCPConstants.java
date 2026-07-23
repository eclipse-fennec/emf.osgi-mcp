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
package org.eclipse.fennec.mcp.emf.runtime;

/**
 * Constants for the EMF metamodel MCP capability/requirement namespace. Used to
 * declare that a bundle provides or requires the EMF metamodel MCP integration.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public interface EmfMCPConstants {

	/** Implementation capability name for the EMF metamodel MCP integration. */
	public static final String MCP_EMF_IMPLEMENTATION = "mcp.emf";
	/** Version of the EMF metamodel MCP capability. */
	public static final String MCP_EMF_VERSION = "1.0";

}
