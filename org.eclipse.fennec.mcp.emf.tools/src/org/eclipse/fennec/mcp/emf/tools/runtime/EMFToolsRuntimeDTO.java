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
package org.eclipse.fennec.mcp.emf.tools.runtime;

import org.osgi.dto.DTO;

/**
 * Snapshot of the complete EMF MCP tools runtime state.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public class EMFToolsRuntimeDTO extends DTO {

	/** The model guard policy, or {@code null} if no guard is active. */
	public GuardPolicyDTO guardPolicy;

	/** The session package registry policy. */
	public PackageRegistryPolicyDTO packagePolicy;

	/** All active MCP sessions with their datasets and registered packages, sorted by session id. */
	public SessionDTO[] sessions;
}
