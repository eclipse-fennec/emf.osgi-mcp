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
 * One MCP session with its datasets and session-registered packages.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public class SessionDTO extends DTO {

	/** The MCP session id. */
	public String sessionId;

	/** Last access epoch millis (the newer of dataset and package store access). */
	public long lastAccess;

	/** The session's datasets, in creation order. */
	public DatasetDTO[] datasets;

	/** The session's registered packages, in registration order. */
	public RegisteredPackageDTO[] registeredPackages;
}
