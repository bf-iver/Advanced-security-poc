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
package org.apache.logging.log4j.core.script;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.util.ExtensionLanguageMapping;
import org.apache.logging.log4j.core.util.FileUtils;
import org.apache.logging.log4j.core.util.IOUtils;
import org.apache.logging.log4j.core.util.NetUtils;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Container for the language and body of a script file along with the file location.
 */
@Plugin(name = "ScriptFile", category = Node.CATEGORY, printObject = true)
public class ScriptFile extends AbstractScript {

    private static final Logger logger = StatusLogger.getLogger();
    private final Path filePath;
    private final boolean isWatched;


    public ScriptFile(String name, Path filePath, String language, boolean isWatched, String scriptText) {
        super(name, language, scriptText);
        this.filePath = filePath;
        this.isWatched = isWatched;
    }

    public Path getPath() {
        return this.filePath;
    }

    public boolean isWatched() {
        return isWatched;
    }

    @PluginFactory
    public static ScriptFile createScript(
            // @formatter:off
            @PluginAttribute("name") String name,
            @PluginAttribute("language") String language, 
            @PluginAttribute("path") final String filePathOrUri,
            @PluginAttribute("isWatched") final Boolean isWatched,
            @PluginAttribute("charset") final Charset charset) {
            // @formatter:on
        if (filePathOrUri == null) {
            logger.error("No script path provided for ScriptFile");
            return null;
        }
        if (name == null) {
            name = filePathOrUri;
        }
        final URI uri = NetUtils.toURI(filePathOrUri);
        final File file = FileUtils.fileFromUri(uri);
        if (language == null && file != null) {
            String fileExtension = FileUtils.getFileExtension(file);
            if (fileExtension != null) {
                ExtensionLanguageMapping mapping = ExtensionLanguageMapping.getByExtension(fileExtension);
                if (mapping != null) {
                    language = mapping.getLanguage();
                }
            }
        }
        if (language == null) {
            logger.info("No script language supplied, defaulting to {}", DEFAULT_LANGUAGE);
            language = DEFAULT_LANGUAGE;
        }

        final Charset actualCharset = charset == null ? Charset.defaultCharset() : charset;
        String scriptText;
        try (final Reader reader = new InputStreamReader(
                file != null ? new FileInputStream(file) : uri.toURL().openStream(), actualCharset)) {
            scriptText = IOUtils.toString(reader);
        } catch (IOException e) {
            logger.error("{}: language={}, path={}, actualCharset={}", e.getClass().getSimpleName(),
                    language, filePathOrUri, actualCharset);
            return null;
        }
        Path path = file != null ? Paths.get(file.toURI()) : Paths.get(uri);
        if (path == null) {
            logger.error("Unable to convert {} to a Path", uri.toString());
            return null;
        }
        return new ScriptFile(name, path, language, isWatched == null ? Boolean.FALSE : isWatched, scriptText);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (!(getName().equals(filePath))) {
            sb.append("name=").append(getName()).append(", ");
        }
        sb.append("path=").append(filePath);
        if (getLanguage() != null) {
            sb.append(", language=").append(getLanguage());
        }
        sb.append(", isWatched=").append(isWatched);
        return sb.toString();
    }
}
