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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.Diagnostician;

/**
 * Maps EMF {@link Diagnostician} results to an agent-friendly, structured
 * validation report. The number of findings returned inline is capped; the
 * remainder is summarized so a huge invalid dataset cannot blow up the
 * response.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public final class ValidationReports {

	/** Maximum number of findings returned inline in one report. */
	public static final int MAX_FINDINGS = 100;

	private ValidationReports() {
	}

	/**
	 * Validates all root objects of the dataset.
	 *
	 * @param dataset the dataset to validate
	 * @return a structured report: valid flag, error/warning counts and capped findings
	 */
	public static Map<String, Object> validate(Dataset dataset) {
		List<Map<String, Object>> findings = new ArrayList<>();
		int errors = 0;
		int warnings = 0;
		for (EObject root : dataset.roots()) {
			Diagnostic diagnostic = Diagnostician.INSTANCE.validate(root);
			for (Diagnostic child : flatten(diagnostic)) {
				if (child.getSeverity() == Diagnostic.ERROR) {
					errors++;
				} else if (child.getSeverity() == Diagnostic.WARNING) {
					warnings++;
				} else {
					continue;
				}
				if (findings.size() < MAX_FINDINGS) {
					findings.add(toFinding(dataset, child));
				}
			}
		}
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("valid", errors == 0);
		report.put("errorCount", errors);
		report.put("warningCount", warnings);
		report.put("findings", findings);
		if (errors + warnings > findings.size()) {
			report.put("truncated", true);
		}
		return report;
	}

	private static List<Diagnostic> flatten(Diagnostic diagnostic) {
		List<Diagnostic> result = new ArrayList<>();
		if (diagnostic.getChildren().isEmpty()) {
			if (diagnostic.getSeverity() != Diagnostic.OK) {
				result.add(diagnostic);
			}
			return result;
		}
		for (Diagnostic child : diagnostic.getChildren()) {
			result.addAll(flatten(child));
		}
		return result;
	}

	private static Map<String, Object> toFinding(Dataset dataset, Diagnostic diagnostic) {
		Map<String, Object> finding = new LinkedHashMap<>();
		finding.put("severity", diagnostic.getSeverity() == Diagnostic.ERROR ? "ERROR" : "WARNING");
		finding.put("message", diagnostic.getMessage());
		for (Object data : diagnostic.getData()) {
			if (data instanceof EObject eObject) {
				String objectId = dataset.idOf(eObject);
				if (objectId != null) {
					finding.put("objectId", objectId);
				}
				finding.put("eClass", eObject.eClass().getName());
			} else if (data instanceof EStructuralFeature feature) {
				finding.put("feature", feature.getName());
			}
		}
		return finding;
	}
}
