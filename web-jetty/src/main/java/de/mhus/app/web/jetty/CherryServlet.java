/**
 * Copyright (C) 2015 Mike Hummel (mh@mhus.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mhus.app.web.jetty;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.service.component.annotations.Component;

import de.mhus.app.web.api.InternalCallContext;
import de.mhus.app.web.core.CherryApiImpl;
import de.mhus.lib.core.logging.MLogUtil;

@Component(
        service = Servlet.class,
        property = "alias=/*",
        name = "CherryServlet",
        servicefactory = true)
public class CherryServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        InternalCallContext call = null;
        CherryApiImpl.instance().beginRequest(this, request, response);
        try {
            call = CherryApiImpl.instance().createCallContext(this, request, response);
            if (call == null) {
                sendNotFoundError(response);
                return;
            }

            call.getVirtualHost().doRequest(call);

        } catch (Throwable t) {
            MLogUtil.log().w(t);
            sendInternalError(response, t);
        } finally {
            CherryApiImpl.instance().endRequest(this, request, response);
        }
    }

    private void sendNotFoundError(HttpServletResponse response) {
        if (response.isCommitted()) return; // can't send error any more
        try {
            response.sendError(404);
        } catch (IOException e) {
        }
    }

    private void sendInternalError(HttpServletResponse response, Throwable t) {
        if (response.isCommitted()) return; // can't send error any more
        try {
            response.sendError(500, t.getMessage());
        } catch (IOException e) {
        }
    }
}
