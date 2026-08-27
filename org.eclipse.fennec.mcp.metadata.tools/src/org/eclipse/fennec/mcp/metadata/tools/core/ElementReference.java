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
package org.eclipse.fennec.mcp.metadata.tools.core;

/**
 * A parsed reference to an element of the metadata layer, in the same notation
 * the EMF model tools already use:
 * <ul>
 * <li>{@code <nsURI>} — an EPackage</li>
 * <li>{@code <nsURI>#//<Name>} — an EClass</li>
 * <li>{@code <nsURI>#//<Name>/<member>} — a structural feature or an EOperation
 * of that class</li>
 * </ul>
 *
 * @param nsURI      the namespace URI, never {@code null}
 * @param className  the class name, {@code null} for a package reference
 * @param memberName the feature or operation name, {@code null} unless a member
 *                   was addressed
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
public record ElementReference(String nsURI, String className, String memberName) {

	private static final String FRAGMENT_SEPARATOR = "#//";

	/** The kind of element a reference addresses. */
	public enum Kind {
		PACKAGE, CLASS, MEMBER
	}

	/**
	 * @return the kind of element this reference addresses
	 */
	public Kind kind() {
		if (className == null) {
			return Kind.PACKAGE;
		}
		return memberName == null ? Kind.CLASS : Kind.MEMBER;
	}

	/**
	 * @return the reference rendered back into its canonical string form
	 */
	public String reference() {
		if (className == null) {
			return nsURI;
		}
		String classReference = nsURI + FRAGMENT_SEPARATOR + className;
		return memberName == null ? classReference : classReference + "/" + memberName;
	}

	/**
	 * Parses an element reference.
	 *
	 * @param reference the reference string
	 * @return the parsed reference, never {@code null}
	 * @throws ToolException if the reference is blank or malformed
	 */
	public static ElementReference parse(String reference) {
		if (reference == null || reference.isBlank()) {
			throw new ToolException("An element reference is required: '<nsURI>', '<nsURI>#//<ClassName>' "
					+ "or '<nsURI>#//<ClassName>/<featureOrOperation>'");
		}
		String trimmed = reference.trim();
		int fragment = trimmed.indexOf(FRAGMENT_SEPARATOR);
		if (fragment < 0) {
			return new ElementReference(trimmed, null, null);
		}
		String nsURI = trimmed.substring(0, fragment);
		String path = trimmed.substring(fragment + FRAGMENT_SEPARATOR.length());
		if (nsURI.isBlank() || path.isBlank()) {
			throw new ToolException(String.format(
					"'%s' is not a valid element reference. Expected '<nsURI>', '<nsURI>#//<ClassName>' "
							+ "or '<nsURI>#//<ClassName>/<featureOrOperation>'", reference));
		}
		int slash = path.indexOf('/');
		if (slash < 0) {
			return new ElementReference(nsURI, path, null);
		}
		String className = path.substring(0, slash);
		String memberName = path.substring(slash + 1);
		if (className.isBlank() || memberName.isBlank() || memberName.indexOf('/') >= 0) {
			throw new ToolException(String.format(
					"'%s' is not a valid element reference. A member is addressed as "
							+ "'<nsURI>#//<ClassName>/<featureOrOperation>'", reference));
		}
		return new ElementReference(nsURI, className, memberName);
	}
}
