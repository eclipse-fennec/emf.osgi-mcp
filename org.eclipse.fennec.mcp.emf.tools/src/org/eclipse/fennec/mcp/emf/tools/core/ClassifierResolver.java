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
package org.eclipse.fennec.mcp.emf.tools.core;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;

/**
 * Resolves a class-reference identifier ({@code <nsURI>#//<Name>}) to an
 * {@link EClassifier} for a single tool call. Instances are produced by
 * {@link ModelGuard#resolverFor(String)} and capture that call's resolution
 * context (the OSGi EPackage registry, the session-local package registry and
 * the built-in Ecore datatypes), so the reflective {@link ModelOperations} stay
 * free of any session or registry knowledge.
 * <p>
 * Resolution is registry-only — a reference is never dereferenced as a URI
 * (no on-demand loading, no external schemes), mirroring {@link ModelGuard}.
 *
 * @author Mark Hoffmann
 * @since Jul 22, 2026
 */
public interface ClassifierResolver {

	/**
	 * Resolves any allowed classifier (concrete or abstract EClass, EDataType or
	 * EEnum) — used to wire {@code eType}, {@code eSuperTypes}, {@code eOpposite}
	 * and other type references.
	 *
	 * @param classifierRef the identifier of the form {@code <nsURI>#//<Name>}
	 * @return the resolved classifier, never {@code null}
	 * @throws ToolException if the reference is malformed, denied or unknown
	 */
	EClassifier resolveClassifier(String classifierRef);

	/**
	 * Resolves an allowed, concrete (instantiable) EClass — used when creating
	 * instances.
	 *
	 * @param eClassRef the identifier of the form {@code <nsURI>#//<ClassName>}
	 * @return the resolved concrete EClass, never {@code null}
	 * @throws ToolException if the reference is malformed, denied, unknown or not concrete
	 */
	EClass resolveConcreteEClass(String eClassRef);

	/**
	 * @return a fail-closed resolver that rejects every class-reference
	 *         identifier — the default when a call has no registry context (e.g.
	 *         a recipe replay that must stay dataset-local).
	 */
	static ClassifierResolver datasetLocalOnly() {
		return DatasetLocal.INSTANCE;
	}

	/**
	 * Fail-closed {@link ClassifierResolver} rejecting any {@code #//} reference.
	 */
	final class DatasetLocal implements ClassifierResolver {

		private static final DatasetLocal INSTANCE = new DatasetLocal();

		private DatasetLocal() {
		}

		@Override
		public EClassifier resolveClassifier(String classifierRef) {
			throw new ToolException(String.format(
					"Reference '%s' points at a registered classifier, which is not resolvable in this context", classifierRef));
		}

		@Override
		public EClass resolveConcreteEClass(String eClassRef) {
			throw new ToolException(String.format(
					"Reference '%s' points at a registered class, which is not resolvable in this context", eClassRef));
		}
	}
}
