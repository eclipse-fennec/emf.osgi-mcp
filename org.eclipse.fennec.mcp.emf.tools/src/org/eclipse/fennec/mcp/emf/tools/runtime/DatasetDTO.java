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
 * One dataset of a session.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public class DatasetDTO extends DTO {

	/** The server-generated dataset id. */
	public String datasetId;

	/** Number of objects currently in the dataset. */
	public int objectCount;

	/** Number of recorded recipe operations. */
	public int recipeSize;

	/** Creation epoch millis. */
	public long createdAt;

	/** Last access epoch millis. */
	public long lastAccess;
}
