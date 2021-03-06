/*
 * (C) Copyright 2006-2015 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Nuxeo - initial API and implementation
 *
 */

package org.nuxeo.runtime.deployment.preprocessor.template;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.TextTemplate;

/**
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
public class Template {

    private static final Log log = LogFactory.getLog(Template.class);

    public static final String BEGIN = "BEGIN";

    public static final String END = "END";

    protected static final String JBOSS5_COMPAT = "org.nuxeo.runtme.preprocessing.jboss5";

    // we should use a linked hash map to preserve the
    // insertion order when iterating over the elements in the map
    final LinkedHashMap<String, Part> parts;

    /**
     * Must be removed when fixing deployment-fragment.xml files.
     */
    protected boolean runningOnJBoss5 = false;

    public Template() {
        parts = new LinkedHashMap<>();
        // XXX compat code
        String v = System.getProperty(JBOSS5_COMPAT);
        if (v != null) {
            runningOnJBoss5 = Boolean.parseBoolean(v);
        }
    }

    public void addPart(String name, String text) {
        parts.put(name, new Part(name, text));
    }

    public void update(TemplateContribution tc, Map<String, String> ctx) {
        String content = getContent(tc, ctx);
        content = new TextTemplate(ctx).processText(content);
        if (tc.isAppending()) {
            appendText(tc.getMarker(), content);
        } else if (tc.isPrepending()) {
            prependText(tc.getMarker(), content);
        } else if (tc.isReplacing()) {
            replaceText(tc.getMarker(), content);
        }
    }

    public void appendText(String marker, String text) {
        Part part = parts.get(marker);
        if (part != null) {
            part.append(text);
        } else {
            log.debug("Could not find marker: " + marker);
        }
    }

    public void prependText(String marker, String text) {
        Part part = parts.get(marker);
        if (part != null) {
            part.prepend(text);
        } else {
            log.debug("Could not find marker: " + marker);
        }
    }

    public void replaceText(String marker, String text) {
        Part part = parts.get(marker);
        if (part != null) {
            part.replace(text);
        } else {
            log.debug("Could not find marker: " + marker);
        }
    }

    public String getText() {
        StringBuilder buf = new StringBuilder();
        for (Part part : parts.values()) {
            buf.append(part.text);
        }
        return buf.toString();
    }

    static class Part {
        public final String name; // the name of the part used in markers

        public final StringBuffer text; // the text before the marker

        public final int offset; // the initial length of the text

        Part(String name, String text) {
            this.name = name;
            this.text = text == null ? new StringBuffer() : new StringBuffer(text);
            offset = this.text.length();
        }

        public void append(String aText) {
            text.append(aText);
        }

        public void prepend(String aText) {
            text.insert(offset, aText);
        }

        public void replace(String aText) {
            text.replace(offset, text.length(), aText);
        }

        public String getText() {
            return text.toString();
        }

        public String getName() {
            return name;
        }

    }

    /*
     * TODO: Remove the following methods when deployment-fragment.xml files will be fixed. These files must not contain
     * <module><java>...</java></module> declarations.
     */

    /**
     * Wrapper method introduced to fix JEE java modules in application template. XXX When this will be solved in trunk
     * you can remove this method and simply call {@code tc.getContent();}.
     */
    protected String getContent(TemplateContribution tc, Map<String, String> context) {
        String content = tc.getContent();
        if (runningOnJBoss5 && "application".equals(tc.getTemplate()) && "MODULE".equals(tc.getMarker())) {
            // remove JEE java modules
            String oldcontent = content;
            content = removeJavaModules(content);
            if (oldcontent != content) {
                log.warn("The deployment-fragment contains illegal JEE java module contribution: "
                        + context.get("bundle.shortName"));
            }
            if (content.length() == 0) {
                return "";
            }
        }
        return "\n" + content.trim() + "\n";
    }

    protected static final Pattern JAVA_MODULE = Pattern.compile("<\\s*module\\s*>\\s*<\\s*java\\s*>.+<\\s*/\\s*java\\s*>\\s*<\\s*/\\s*module\\s*>");

    /**
     * Remove {@code <module><java>...</java></module>} from {@code application.xml} contributions. This a temporary fix
     * to remove incorrect java module declarations from deployment-fragments - this should be fixed in each fragment.
     */
    protected static String removeJavaModules(String content) {
        Matcher m = JAVA_MODULE.matcher(content);
        if (m.find()) {
            return m.replaceAll("").trim();
        }
        return content;
    }

}
