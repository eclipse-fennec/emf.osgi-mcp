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
 * The deny-all allow-lists of the {@code EMFModelGuard}.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
public class GuardPolicyDTO extends DTO {

	/** Allow-listed EPackage namespace URIs (deny-all when empty), sorted. */
	public String[] allowedEPackages;

	/** Allow-listed EClass identifiers ({@code <nsURI>#//<Name>}), sorted. */
	public String[] allowedEClasses;
}
