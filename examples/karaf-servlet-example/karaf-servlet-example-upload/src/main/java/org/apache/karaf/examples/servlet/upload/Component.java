/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.karaf.examples.servlet.upload;

import jakarta.servlet.Servlet;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.servlet.whiteboard.HttpWhiteboardConstants;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Dictionary;
import java.util.Hashtable;

@org.osgi.service.component.annotations.Component
public class Component {

    private ServiceRegistration<Servlet> reg;

    @Activate
    public void activate(BundleContext context) throws Exception {
        final String tmpDir = System.getProperty("java.io.tmpdir");
        final Path uploadPath = Paths.get(tmpDir, "karaf", "upload");
        uploadPath.toFile().mkdirs();
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME, "UploadServlet");
        props.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/upload-example/*");
        reg = context.registerService(Servlet.class, new UploadServlet(uploadPath), props);
    }

    @Deactivate
    public void deactivate() throws Exception {
        if (reg != null) {
            reg.unregister();
        }
    }

}
