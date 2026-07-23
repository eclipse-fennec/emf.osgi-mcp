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
package org.eclipse.fennec.mcp.api.runtime;

import org.osgi.dto.DTO;

/**
 * One tool provider with the tools it matched.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public class MCPToolProviderDTO extends DTO {

	/** The provider name ({@code name} service property), or {@code null} if unnamed. */
	public String name;

	/** The human-readable provider description. */
	public String description;

	/** The names of the tools this provider currently serves, sorted. */
	public String[] tools;
}
