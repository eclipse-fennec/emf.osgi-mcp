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
 * One session-registered (authored or imported) EPackage.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public class RegisteredPackageDTO extends DTO {

	/** The namespace URI the package is registered under. */
	public String nsUri;

	/** The package name. */
	public String name;

	/** Number of classifiers in the package. */
	public int classifierCount;

	/** Last modification epoch millis (registration or rekey). */
	public long lastModified;
}
