/*
 * Copyright (c) 2008-2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package org.jd.core.v1.model.classfile.attribute;

import java.util.Map;

/** Java 16+ 'Record' attribute: the record components, in declaration order. */
public class AttributeRecord implements Attribute {
    protected RecordComponent[] components;

    public AttributeRecord(RecordComponent[] components) {
        this.components = components;
    }

    public RecordComponent[] getComponents() {
        return components;
    }

    public static class RecordComponent {
        protected String name;
        protected String descriptor;
        protected Map<String, Attribute> attributes;

        public RecordComponent(String name, String descriptor, Map<String, Attribute> attributes) {
            this.name = name;
            this.descriptor = descriptor;
            this.attributes = attributes;
        }

        public String getName() {
            return name;
        }

        public String getDescriptor() {
            return descriptor;
        }

        @SuppressWarnings("unchecked")
        public <T extends Attribute> T getAttribute(String name) {
            return (attributes == null) ? null : (T)attributes.get(name);
        }
    }
}
