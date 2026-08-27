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
package org.eclipse.fennec.mcp.metadata.tools;

import java.util.Map;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;

/**
 * Dynamic fixture metamodels shaped like a real Fennec type-mapping family: an
 * abstract parent declaring the discriminator path and concrete subclasses each
 * claiming one discriminator value.
 * <p>
 * The abstract parent is the point of the fixture. It is exactly the class the
 * EMF model tools cannot reach - {@code list_metamodel} filters to concrete
 * classes and {@code describe_eclass} rejects abstract ones - so it is what the
 * annotation queries here have to be able to find.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
final class TestModels {

	static final String UPLINK_NS_URI = "https://example.org/metadata/uplink";
	static final String GATEWAY_NS_URI = "https://example.org/metadata/gateway";

	/** The current spelling. The older {@code codec.type.{mapId}} form is silently ignored. */
	static final String MAP_ID = "testmap";
	static final String TYPE_MAPPING_SOURCE = "http://eclipse.org/fennec/codec/typeMapping/" + MAP_ID;
	static final String KEY_DISCRIMINATOR_PATH = "typeDiscriminatorPath";
	static final String KEY_DISCRIMINATOR = "typeDiscriminator";
	static final String DISCRIMINATOR_PATH = "deviceInfo.deviceProfileName";

	static final String CODEC_SOURCE = "http://eclipse.org/fennec/codec";
	static final String DOCS_SOURCE = "https://example.org/metadata/docs";

	static final String UPLINK_MESSAGE = UPLINK_NS_URI + "#//UplinkMessage";
	static final String SENSOR_A_UPLINK = UPLINK_NS_URI + "#//SensorAUplink";
	static final String SENSOR_B_UPLINK = UPLINK_NS_URI + "#//SensorBUplink";
	static final String GATEWAY = GATEWAY_NS_URI + "#//Gateway";

	private TestModels() {
		// static factory
	}

	/**
	 * @return the family package: abstract {@code UplinkMessage} plus two concrete
	 *         subclasses, in the distributed registration shape
	 */
	static EPackage uplinkPackage() {
		EcoreFactory factory = EcoreFactory.eINSTANCE;
		EPackage uplink = factory.createEPackage();
		uplink.setName("uplink");
		uplink.setNsURI(UPLINK_NS_URI);
		uplink.setNsPrefix("uplink");

		EClass deviceInfo = factory.createEClass();
		deviceInfo.setName("DeviceInfo");
		EAttribute deviceProfileName = factory.createEAttribute();
		deviceProfileName.setName("deviceProfileName");
		deviceProfileName.setEType(EcorePackage.Literals.ESTRING);
		annotate(deviceProfileName, CODEC_SOURCE, Map.of("key", "deviceProfileName"));
		deviceInfo.getEStructuralFeatures().add(deviceProfileName);
		uplink.getEClassifiers().add(deviceInfo);

		EClass uplinkMessage = factory.createEClass();
		uplinkMessage.setName("UplinkMessage");
		uplinkMessage.setAbstract(true);
		annotate(uplinkMessage, TYPE_MAPPING_SOURCE, Map.of(KEY_DISCRIMINATOR_PATH, DISCRIMINATOR_PATH));
		EReference deviceInfoReference = factory.createEReference();
		deviceInfoReference.setName("deviceInfo");
		deviceInfoReference.setEType(deviceInfo);
		deviceInfoReference.setContainment(true);
		uplinkMessage.getEStructuralFeatures().add(deviceInfoReference);
		EOperation describe = factory.createEOperation();
		describe.setName("describe");
		describe.setEType(EcorePackage.Literals.ESTRING);
		annotate(describe, DOCS_SOURCE, Map.of("summary", "Human readable form"));
		uplinkMessage.getEOperations().add(describe);
		uplink.getEClassifiers().add(uplinkMessage);

		uplink.getEClassifiers().add(subclass(factory, uplinkMessage, "SensorAUplink", "Sensor_A", "temperature"));
		uplink.getEClassifiers().add(subclass(factory, uplinkMessage, "SensorBUplink", "Sensor_B", "humidity"));
		return uplink;
	}

	/**
	 * @return a second, unrelated package, so cross-package lookup has something to
	 *         find that the caller never named
	 */
	static EPackage gatewayPackage() {
		EcoreFactory factory = EcoreFactory.eINSTANCE;
		EPackage gateway = factory.createEPackage();
		gateway.setName("gateway");
		gateway.setNsURI(GATEWAY_NS_URI);
		gateway.setNsPrefix("gateway");

		EClass gatewayClass = factory.createEClass();
		gatewayClass.setName("Gateway");
		EAttribute gatewayId = factory.createEAttribute();
		gatewayId.setName("gatewayId");
		gatewayId.setEType(EcorePackage.Literals.ESTRING);
		gatewayClass.getEStructuralFeatures().add(gatewayId);
		gateway.getEClassifiers().add(gatewayClass);
		return gateway;
	}

	private static EClass subclass(EcoreFactory factory, EClass parent, String name, String discriminator,
			String attributeName) {
		EClass eClass = factory.createEClass();
		eClass.setName(name);
		eClass.getESuperTypes().add(parent);
		annotate(eClass, TYPE_MAPPING_SOURCE, Map.of(KEY_DISCRIMINATOR, discriminator));
		EAttribute attribute = factory.createEAttribute();
		attribute.setName(attributeName);
		attribute.setEType(EcorePackage.Literals.EDOUBLE);
		eClass.getEStructuralFeatures().add(attribute);
		return eClass;
	}

	private static void annotate(EModelElement element, String source, Map<String, String> details) {
		EAnnotation annotation = EcoreFactory.eINSTANCE.createEAnnotation();
		annotation.setSource(source);
		details.forEach((key, value) -> annotation.getDetails().put(key, value));
		element.getEAnnotations().add(annotation);
	}
}
