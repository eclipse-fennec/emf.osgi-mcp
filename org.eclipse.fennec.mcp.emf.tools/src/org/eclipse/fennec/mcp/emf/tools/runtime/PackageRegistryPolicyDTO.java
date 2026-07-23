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
 * The registration policy of the session package registry
 * ({@code EMFPackageRegistry} PID).
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public class PackageRegistryPolicyDTO extends DTO {

	/** nsURI allow patterns (empty = deny all; {@code *} = allow all; trailing {@code *} = prefix). */
	public String[] nsUriAllowList;

	/** nsURI deny patterns, applied after the allow list. */
	public String[] nsUriDenyList;

	/** Maximum registered packages per session (LRU eviction beyond). */
	public int maxModels;
}
