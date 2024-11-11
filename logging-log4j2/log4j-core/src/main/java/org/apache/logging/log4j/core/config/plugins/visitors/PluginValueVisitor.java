/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */

package org.apache.logging.log4j.core.config.plugins.visitors;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.PluginValue;
import org.apache.logging.log4j.util.StringBuilders;

/**
 * PluginVisitor implementation for {@link PluginValue}.
 */
public class PluginValueVisitor extends AbstractPluginVisitor<PluginValue> {
    public PluginValueVisitor() {
        super(PluginValue.class);
    }

    @Override
    public Object visit(final Configuration configuration, final Node node, final LogEvent event,
                        final StringBuilder log) {
        final String name = this.annotation.value();
        final String rawValue = node.getValue() != null ? node.getValue() :
            removeAttributeValue(node.getAttributes(), "value");
        final String value = this.substitutor.replace(event, rawValue);
        StringBuilders.appendKeyDqValue(log, name, value);
        return value;
    }
}