/*
 * (C) Copyright 2016 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Ricardo Dias
 */

package org.nuxeo.ecm.automation.core.operations.document;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationChain;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.*;

import com.google.inject.Inject;

/**
 * @author rdias
 * @since 8.3
 */
@RunWith(FeaturesRunner.class)
@Features({ CoreFeature.class })
@Deploy("org.nuxeo.ecm.automation.core")
@LocalDeploy("org.nuxeo.ecm.automation.core:OSGI-INF/dataset-type-test-contrib.xml")
public class AddItemToListPropertyTest {

    @Inject
    AutomationService service;

    @Inject
    CoreSession coreSession;

    protected DocumentModel testFolder;

    protected DocumentModel doc;

    @Before
    public void initRepo() throws Exception {
        coreSession.removeChildren(coreSession.getRootDocument().getRef());
        coreSession.save();

        testFolder = coreSession.createDocumentModel("/", "Folder", "Folder");
        testFolder.setPropertyValue("dc:title", "Folder");
        testFolder = coreSession.createDocument(testFolder);
        coreSession.save();
        testFolder = coreSession.getDocument(testFolder.getRef());

        // creates a document of custom type DataSet
        doc = coreSession.createDocumentModel("/Folder", "TestDoc", "DataSet");
        doc.setPropertyValue("dc:title", "TestDoc");
        doc = coreSession.createDocument(doc);
        coreSession.save();
        doc = coreSession.getDocument(doc.getRef());
    }

    @After
    public void clearRepo() {
        coreSession.removeChildren(coreSession.getRootDocument().getRef());
        coreSession.save();
    }

    @Test
    public void addItemToListPropertyTest() throws IOException, OperationException {

        // check if the doc exists
        assertNotNull(doc);

        // Check there is no value already.
        assertNotNull(doc.getPropertyValue("ds:fields"));
        assertEquals(((Collection) doc.getPropertyValue("ds:fields")).size(), 0);

        String fieldsDataAsJSon = readPropertiesFromFile("creationFields.json");

        // Add first fields
        OperationContext ctx = new OperationContext(coreSession);
        ctx.setInput(doc);
        OperationChain chain = new OperationChain("testAddItemToPropertyChain");
        chain.add(AddItemToListProperty.ID).set("xpath", "ds:fields").set("ComplexJsonProperties", fieldsDataAsJSon);

        DocumentModel resultDoc = (DocumentModel) service.run(ctx, chain);
        java.util.ArrayList<?> dbFields = (java.util.ArrayList<?>) resultDoc.getPropertyValue("ds:fields");
        assertEquals(5, dbFields.size());

    }

    @Test
    public void addMoreItemToListPropertyTest() throws IOException, OperationException {

        String fieldsDataAsJSon = readPropertiesFromFile("newFields.json");

        // ADD new fields
        OperationContext ctx = new OperationContext(coreSession);
        ctx.setInput(doc);
        OperationChain chain = new OperationChain("testChain");
        chain.add(AddItemToListProperty.ID).set("xpath", "ds:fields").set("ComplexJsonProperties", fieldsDataAsJSon);

        DocumentModel resultDoc = (DocumentModel) service.run(ctx, chain);
        java.util.ArrayList<?> dbFields = (java.util.ArrayList<?>) resultDoc.getPropertyValue("ds:fields");
        assertEquals(2, dbFields.size());
    }

    @Test(expected = OperationException.class)
    public void addItemToNonListPropertyTest() throws IOException, OperationException {

        // Get new fields from json file to String
        String fieldsDataAsJSon = readPropertiesFromFile("newFields.json");
        // ADD new fields
        OperationContext ctx = new OperationContext(coreSession);
        ctx.setInput(doc);
        OperationChain chain = new OperationChain("testChain");
        chain.add(AddItemToListProperty.ID).set("xpath", "dc:title").set("ComplexJsonProperties", fieldsDataAsJSon);
        DocumentModel resultDoc = (DocumentModel) service.run(ctx, chain);

    }

    protected String readPropertiesFromFile(String filename) throws IOException {
        File fieldsAsJsonFile = FileUtils.getResourceFileFromContext(filename);
        assertNotNull(fieldsAsJsonFile);
        String fieldsDataAsJSon = FileUtils.readFile(fieldsAsJsonFile);
        fieldsDataAsJSon = fieldsDataAsJSon.replaceAll("\n", "");
        fieldsDataAsJSon = fieldsDataAsJSon.replaceAll("\r", "");

        return fieldsDataAsJSon;
    }

}