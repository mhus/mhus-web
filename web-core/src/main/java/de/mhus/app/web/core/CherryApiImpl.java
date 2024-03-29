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
package de.mhus.app.web.core;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.WeakHashMap;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.shiro.subject.Subject;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import de.mhus.app.web.api.CallContext;
import de.mhus.app.web.api.CherryApi;
import de.mhus.app.web.api.InternalCallContext;
import de.mhus.app.web.api.TypeHeader;
import de.mhus.app.web.api.TypeHeaderDynamic;
import de.mhus.app.web.api.TypeHeaderFactory;
import de.mhus.app.web.api.TypeHeaderSimple;
import de.mhus.app.web.api.VirtualHost;
import de.mhus.app.web.api.WebSession;
import de.mhus.lib.core.M;
import de.mhus.lib.core.MFile;
import de.mhus.lib.core.MLog;
import de.mhus.lib.core.MThread;
import de.mhus.lib.core.aaa.Aaa;
import de.mhus.lib.core.aaa.SubjectEnvironment;
import de.mhus.lib.core.cfg.CfgInt;
import de.mhus.lib.core.logging.ITracer;
import de.mhus.lib.core.node.INode;
import de.mhus.lib.errors.MException;
import de.mhus.lib.servlet.security.SecurityApi;
import de.mhus.osgi.api.util.AbstractServiceTracker;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;

@Component(immediate = true)
public class CherryApiImpl extends MLog implements CherryApi {

    public static final String SESSION_PARAMETER_SESSION = "__cherry_global_session";
    private static CfgInt CFG_MAX_VHOST_CACHE_SIZE =
            new CfgInt(CherryApi.class, "maxVHostCacheSize", 200);

    private static CherryApiImpl instance;
    private ThreadLocal<CallContext> calls = new ThreadLocal<>();
    private WeakHashMap<String, WebSession> globalSession = new WeakHashMap<>();
    private HashMap<String, VirtualHost> vHosts = new HashMap<>();
    private HashMap<String, VirtualHost> vHostsCache = new HashMap<>();
    private LinkedList<TypeHeaderFactory> typeHeaderFactories = new LinkedList<>();

    {
        typeHeaderFactories.add(new TypeHeaderDynamic.Factory());
    }

    AbstractServiceTracker<VirtualHost> vHostTracker =
            new AbstractServiceTracker<VirtualHost>(VirtualHost.class) {

                @Override
                protected void removeService(
                        ServiceReference<VirtualHost> reference, VirtualHost service) {
                    removeVirtualHost(service);
                    service.setBundle(null);
                }

                @Override
                protected void addService(
                        ServiceReference<VirtualHost> reference, VirtualHost service) {
                    service.setBundle(reference.getBundle());
                    addVirtualHost(service);
                }
            };

    public static CherryApiImpl instance() {
        return instance;
    }

    protected void addVirtualHost(VirtualHost service) {
        synchronized (vHosts) {
            vHostsCache.clear();
            try {
                service.start(this);
            } catch (Throwable t) {
                log().e("Can't add virtual host", service.getName(), t);
                return;
            }
            Set<String> aliases = service.getVirtualHostAliases();
            for (String alias : aliases) {
                log().i("add virtual host", alias);
                VirtualHost old = vHosts.put(alias, service);
                if (old != null) old.stop(this);
            }
        }
    }

    protected void removeVirtualHost(VirtualHost service) {
        synchronized (vHosts) {
            vHostsCache.clear();
            vHosts.entrySet()
                    .removeIf(
                            e -> {
                                if (service == e.getValue()) {
                                    log().i("remove virtual host", e.getKey());
                                    return true;
                                }
                                return false;
                            });
            service.stop(this);
        }
    }

    @Activate
    public void doActivate(ComponentContext ctx) {
        log().i("Start Cherry");
        instance = this;
        vHostTracker.start(ctx);
    }

    @Deactivate
    public void doDeactivate(ComponentContext ctx) {
        log().i("Stop Cherry");
        vHostTracker.stop();
        instance = null;
    }

    @Override
    public CallContext getCurrentCall() {
        return calls.get();
    }

    @Override
    public VirtualHost findVirtualHost(String host) {
        synchronized (vHosts) {
            // get from cache
            VirtualHost vHost = null;
            vHost = vHostsCache.get(host);
            if (vHost != null) return vHost;

            // lookup
            vHost = vHosts.get(host);
            if (vHost == null) {
                // remove port
                String h = host;
                int p = h.indexOf(':');
                if (p > 0) h = h.substring(0, p) + ":*";
                else h = h + ":*";
                vHost = vHosts.get(h);
            }
            if (vHost == null) {
                vHost = vHosts.get("*");
            }
            // save to cache
            if (vHost != null && vHostsCache.size() < CFG_MAX_VHOST_CACHE_SIZE.value())
                vHostsCache.put(host, vHost);
            return vHost;
        }
    }

    @Override
    public String getMimeType(String file) {
        String extension = MFile.getFileExtension(file);
        return MFile.getMimeType(extension);
    }

    public boolean isCherrySession(String sessionId) {
        WebSession ret = globalSession.get(sessionId);
        return ret != null;
    }

    public WebSession getCherrySession(CallContext context, String sessionId) {
        WebSession ret = globalSession.get(sessionId);
        if (ret == null) {
            if (context == null) return null;
            ret = new CherrySession(sessionId);
            globalSession.put(sessionId, ret);
            // put into http session to create a reference until http session time out
            context.getHttpRequest()
                    .getSession()
                    .setAttribute(SESSION_PARAMETER_SESSION, globalSession);
        }
        return ret;
    }

    public void setCallContext(CherryCallContext callContext) {
        if (callContext != null) calls.set(callContext);
        else calls.remove();
    }

    @Override
    public InternalCallContext createCallContext(
            Servlet servlet, HttpServletRequest request, HttpServletResponse response)
            throws MException {

        // check general security
        SecurityApi sec = M.l(SecurityApi.class);
        if (sec != null) {
            if (!sec.checkHttpRequest(request, response)) return null;
            if (response.isCommitted()) return null;
        }

        // find vhost
        String host = request.getHeader("Host");
        VirtualHost vHost = findVirtualHost(host);
        if (vHost == null) return null;

        // create call context
        CherryCallContext call = new CherryCallContext(servlet, request, response, vHost);

        return call;
    }

    @Override
    public Map<String, VirtualHost> getVirtualHosts() {
        return Collections.unmodifiableMap(vHosts);
    }

    @Override
    public void restart(VirtualHost host) {
        removeVirtualHost(host);
        addVirtualHost(host);
    }

    public void beginRequest(
            Servlet servlet, HttpServletRequest request, HttpServletResponse response) {
        if (request == null) return;

        // set user
        HttpSession session = request.getSession(false);
        // set aaa context
        @SuppressWarnings("unused")
        SubjectEnvironment access = null;
        MThread.cleanup();
        if (session != null && session.getAttribute("_access_session_id") != null) {
            Subject subject =
                    Aaa.createSubjectFromSessionId(
                            (String) session.getAttribute("_access_session_id"));
            request.setAttribute("_access_subject", subject);
            access = Aaa.asSubject(subject);
        }
        // reset aaa context on error
        try {
            // tracing
            Scope scope = null;
            SpanContext parentSpanCtx =
                    ITracer.get()
                            .tracer()
                            .extract(
                                    Format.Builtin.HTTP_HEADERS,
                                    new TextMap() {

                                        @Override
                                        public Iterator<Entry<String, String>> iterator() {
                                            final Enumeration<String> enu =
                                                    request.getHeaderNames();
                                            return new Iterator<Entry<String, String>>() {
                                                @Override
                                                public boolean hasNext() {
                                                    return enu.hasMoreElements();
                                                }

                                                @Override
                                                public Entry<String, String> next() {
                                                    final String key = enu.nextElement();
                                                    return new Entry<String, String>() {

                                                        @Override
                                                        public String getKey() {
                                                            return key;
                                                        }

                                                        @Override
                                                        public String getValue() {
                                                            return request.getHeader(key);
                                                        }

                                                        @Override
                                                        public String setValue(String value) {
                                                            return null;
                                                        }
                                                    };
                                                }
                                            };
                                        }

                                        @Override
                                        public void put(String key, String value) {}
                                    });

            String trace = request.getParameter("_trace");
            if (parentSpanCtx == null) {
                scope = ITracer.get().start("rest", trace);
            } else if (parentSpanCtx != null) {
                Span span =
                        ITracer.get().tracer().buildSpan("rest").asChildOf(parentSpanCtx).start();
                scope = ITracer.get().activate(span);
                ITracer.get().activate(trace);
            }

            Span span = ITracer.get().current();
            if (span != null) {
                Tags.SPAN_KIND.set(span, Tags.SPAN_KIND_SERVER);
                Tags.HTTP_METHOD.set(span, request.getMethod());
                Tags.HTTP_URL.set(span, request.getRequestURL().toString());
            }

            request.setAttribute("_tracer_scope", scope);
        } catch (Throwable t) {
            Aaa.subjectCleanup();
            throw t;
        }
    }

    public void endRequest(
            Servlet servlet, HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute("_tracer_scope") != null) {
            // could also use ScopeManager
            ((Scope) request.getAttribute("_tracer_scope")).close();
        }

        MThread.cleanup();
    }

    @Override
    public TypeHeader createTypeHeader(INode header) throws MException {
        synchronized (typeHeaderFactories) {
            for (TypeHeaderFactory factory : typeHeaderFactories) {
                TypeHeader obj = factory.create(header);
                if (obj != null) return obj;
            }
        }
        // fallback
        String key = header.getString("key", null);
        if (key == null) return null;
        String value = header.getString("value", "");
        boolean add = header.getBoolean("add", false);
        return new TypeHeaderSimple(key, value, add);
    }

    @Reference(
            service = TypeHeaderFactory.class,
            cardinality = ReferenceCardinality.MULTIPLE,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "removeTypeHeaderFactory")
    public void addTypeHeaderFactory(TypeHeaderFactory factory) {
        synchronized (typeHeaderFactories) {
            typeHeaderFactories.addFirst(factory);
        }
    }

    public void removeTypeHeaderFactory(TypeHeaderFactory factory) {
        synchronized (typeHeaderFactories) {
            typeHeaderFactories.remove(factory);
        }
    }

    public LinkedList<TypeHeaderFactory> getTypeHeaderFactories() {
        return typeHeaderFactories;
    }
}
