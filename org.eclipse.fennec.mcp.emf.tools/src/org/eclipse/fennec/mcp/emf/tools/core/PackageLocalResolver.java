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

import java.util.List;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;

/**
 * A {@link ClassifierResolver} that resolves references to the EPackage
 * currently being authored before falling back to the registry-backed
 * resolver. A local reference is written {@code #//<Name>} or, equivalently,
 * with the package's own namespace ({@code <nsURI>#//<Name>} — the form the
 * exported .ecore uses).
 * <p>
 * This is what lets one composite {@code create_epackage} declare classifiers
 * that reference each other regardless of declaration order: the classifiers
 * are all attached to the package first, then their types are wired through a
 * resolver that can see them. It also lets {@code add_eclass} point at sibling
 * classes of the same package, which the registry cannot resolve while the
 * package is still unregistered.
 * <p>
 * Resolution stays registry-only in the delegate — nothing here dereferences a
 * reference as a URI.
 *
 * @author Mark Hoffmann
 * @since Aug 28, 2026
 */
public final class PackageLocalResolver implements ClassifierResolver {

	private final ClassifierResolver delegate;
	private final EPackage ePackage;
	private final List<EClassifier> additional;

	/**
	 * @param delegate   the resolver handling everything that is not local to
	 *                   {@code ePackage}
	 * @param ePackage   the package under construction
	 * @param additional classifiers of this package that are not attached to it
	 *                   (yet), e.g. the class a composite add_eclass is building
	 */
	public PackageLocalResolver(ClassifierResolver delegate, EPackage ePackage, List<EClassifier> additional) {
		this.delegate = delegate;
		this.ePackage = ePackage;
		this.additional = List.copyOf(additional);
	}

	@Override
	public EClassifier resolveClassifier(String classifierRef) {
		EClassifier local = local(classifierRef);
		return local == null ? delegate.resolveClassifier(classifierRef) : local;
	}

	@Override
	public EClass resolveConcreteEClass(String eClassRef) {
		EClassifier local = local(eClassRef);
		if (local == null) {
			return delegate.resolveConcreteEClass(eClassRef);
		}
		if (!(local instanceof EClass eClass)) {
			throw new ToolException(String.format("'%s' is a %s, not an EClass", eClassRef, local.eClass().getName()));
		}
		if (eClass.isAbstract() || eClass.isInterface()) {
			throw new ToolException(String.format("EClass '%s' is abstract and cannot be instantiated", eClassRef));
		}
		return eClass;
	}

	/**
	 * @return the classifier of the package under construction the reference
	 *         denotes, or {@code null} if the reference is not local to it
	 */
	private EClassifier local(String ref) {
		if (ref == null) {
			return null;
		}
		int separator = ref.indexOf(ModelGuard.CLASS_REF_SEPARATOR);
		if (separator < 0) {
			return null;
		}
		String nsURI = ref.substring(0, separator);
		// an empty namespace means "this document"; the package's own nsURI is the
		// same reference written out in full
		if (!nsURI.isEmpty() && !nsURI.equals(ePackage.getNsURI())) {
			return null;
		}
		String name = ref.substring(separator + ModelGuard.CLASS_REF_SEPARATOR.length());
		for (EClassifier classifier : additional) {
			if (name.equals(classifier.getName())) {
				return classifier;
			}
		}
		// scan rather than EPackage.getEClassifier(String): classifiers are added
		// while this resolver is in use, and the name map behind that accessor is
		// cached
		for (EClassifier classifier : ePackage.getEClassifiers()) {
			if (name.equals(classifier.getName())) {
				return classifier;
			}
		}
		return null;
	}
}
