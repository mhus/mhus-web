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
package de.mhus.app.web.util.webspace;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import de.mhus.app.web.api.CallContext;
import de.mhus.app.web.api.CanTransform;
import de.mhus.app.web.api.CherryApi;
import de.mhus.app.web.util.CherryWebUtil;
import de.mhus.lib.core.IReadProperties;
import de.mhus.lib.core.M;
import de.mhus.lib.core.MCollection;
import de.mhus.lib.core.MDate;
import de.mhus.lib.core.MFile;
import de.mhus.lib.core.MProperties;
import de.mhus.lib.core.MString;
import de.mhus.lib.core.MSystem;
import de.mhus.lib.core.crypt.MRandom;
import de.mhus.lib.core.io.http.MHttp;
import de.mhus.lib.core.node.INode;
import de.mhus.lib.core.util.SoftHashMap;
import de.mhus.lib.errors.MException;
import de.mhus.osgi.transform.api.TransformUtil;

public class TransformWebSpace extends AbstractWebSpace
        implements CanTransform, CallConfigProvider {

    protected INode cDir;
    protected String index = "index";
    protected String[] extensionOrder = new String[] {".twig"};
    protected String[] removeExtensions = new String[] {".html", ".htm"};
    protected String[] htmlExtensions = new String[] {".html", ".htm"};
    protected String[] denyExtensions = new String[] {".cfg"};
    protected String cfgExtension = ".cfg";
    protected File templateRoot;
    protected File errorTemplate = null;
    protected MProperties environment = null;
    private boolean csrfEnabled;
    private int stamp = 0;
    private SoftHashMap<File, MProperties> cfgCache = new SoftHashMap<>();
    private MProperties cfgDefault = new MProperties();
    private String htmlHeader;
    private String htmlFooter;

    @Override
    public void start(CherryApi api) throws MException {

        MRandom rnd = M.l(MRandom.class);
        stamp = rnd.getInt();

        super.start(api);
        cDir = getConfig().getObject("transform");
        templateRoot = getDocumentRoot();
        environment = new MProperties();
        if (cDir != null) {
            charsetEncoding = cDir.getString("characterEncoding", charsetEncoding);
            if (cDir.isProperty("index")) index = cDir.getString("index");
            if (cDir.isProperty("templateRoot"))
                templateRoot = findTemplateFile(cDir.getString("templateRoot"));
            if (cDir.isProperty("extensionOrder")) {
                extensionOrder =
                        INode.toStringArray(cDir.getObject("extensionOrder").getObjects(), "value");
                MCollection.updateEach(extensionOrder, e -> "." + e.toLowerCase());
            }
            if (cDir.isProperty("denyExtensions")) {
                denyExtensions =
                        INode.toStringArray(cDir.getObject("denyExtensions").getObjects(), "value");
                MCollection.updateEach(denyExtensions, e -> "." + e.toLowerCase());
            }
            if (cDir.isProperty("removeExtensions")) {
                removeExtensions =
                        INode.toStringArray(
                                cDir.getObject("removeExtensions").getObjects(), "value");
                MCollection.updateEach(removeExtensions, e -> "." + e.toLowerCase());
            }
            if (cDir.isProperty("htmlExtensions")) {
                htmlExtensions =
                        INode.toStringArray(cDir.getObject("htmlExtensions").getObjects(), "value");
                MCollection.updateEach(htmlExtensions, e -> "." + e.toLowerCase());
            }
            if (cDir.isProperty("header")) {
                String header = cDir.getString("header");
                File htmlHeaderF = new File(getDocumentRoot(), header);
                if (!htmlHeaderF.exists()) {
                    log().w("ignore html header", htmlHeaderF.getAbsolutePath());
                } else htmlHeader = htmlHeaderF.getAbsolutePath();
            }
            if (cDir.isProperty("footer")) {
                String footer = cDir.getString("footer");
                File htmlFooterF = new File(getDocumentRoot(), footer);
                if (!htmlFooterF.exists()) {
                    log().w("ignore html footer", htmlFooterF.getAbsolutePath());
                } else htmlFooter = htmlFooterF.getAbsolutePath();
            }
            if (cDir.isProperty("error")) {
                String error = cDir.getString("error");
                errorTemplate = new File(getDocumentRoot(), error);
                if (!errorTemplate.exists()) {
                    log().w("ignore error template", errorTemplate.getAbsolutePath());
                }
            }
            if (cDir.containsKey("cfgExtension"))
                cfgExtension = "." + cDir.getString("cfgExtension").toLowerCase();

            csrfEnabled = cDir.getBoolean("csrfEnabled", true);
            INode cEnv = cDir.getObject("environment");
            if (cEnv != null) {
                for (String key : cEnv.getPropertyKeys()) {
                    environment.put(key, cEnv.get(key));
                }
            }
        }
    }

    @Override
    protected void doDeleteRequest(CallContext context) throws Exception {}

    @Override
    protected void doPutRequest(CallContext context) throws Exception {}

    @Override
    protected void doPostRequest(CallContext context) throws Exception {}

    @Override
    protected void doHeadRequest(CallContext context) throws Exception {
        String path = context.getHttpPath();
        path = MFile.normalizePath(path);
        File file = new File(templateRoot, path);
        String orgPath = path;
        String lowerPath = path.toLowerCase();
        if (file.exists()) {
            if (file.isDirectory()) {
                path = path + "/" + index;
            } else if (hasTransformExtension(lowerPath)) {
                // path = MString.beforeLastIndex(path, '.');
                sendError(context, HttpServletResponse.SC_NOT_FOUND, null);
                return;
            } else {
                IReadProperties fileConfig = findConfig(file);
                prepareHead(context, file, path, fileConfig);
                return;
            }
        }
        // deny ?
        for (String extension : denyExtensions) {
            if (lowerPath.endsWith(extension)) {
                sendError(context, HttpServletResponse.SC_NOT_FOUND, null);
                return;
            }
        }
        // find template
        for (String extension : removeExtensions) {
            if (lowerPath.endsWith(extension)) {
                path = path.substring(0, path.length() - extension.length());
                break;
            }
        }

        for (String extension : extensionOrder) {
            String p = path + extension;
            file = new File(templateRoot, p);
            if (file.exists() && file.isFile()) {
                IReadProperties fileConfig = findConfig(file);
                prepareHead(context, file, orgPath, fileConfig);
            }
        }

        sendError(context, HttpServletResponse.SC_NOT_FOUND, null);
    }

    @Override
    protected void doGetRequest(CallContext context) throws Exception {
        String path = context.getHttpPath();
        path = MFile.normalizePath(path);
        File file = new File(templateRoot, path);

        if (file.exists() && file.isDirectory()) {
            path = path + "/" + index;
            file = new File(templateRoot, path);
        }
        String lowerPath = path.toLowerCase();
        // deny ?
        for (String extension : denyExtensions) {
            if (lowerPath.endsWith(extension)) {
                sendError(context, HttpServletResponse.SC_NOT_FOUND, null);
                return;
            }
        }

        if (file.exists()) {

            IReadProperties fileConfig = findConfig(file);
            if (file.isDirectory()) {
                log().d("deny directory", file);
                sendError(context, HttpServletResponse.SC_NOT_FOUND, null);
                return;
            }
            if (hasTransformExtension(lowerPath)) {
                log().d("deny TransformExtension", path);
                // path = MString.beforeLastIndex(path, '.');
                sendError(context, HttpServletResponse.SC_NOT_FOUND, null);
                return;
            } else {
                prepareHead(context, file, path, fileConfig);
                try {
                    boolean isHtml = hasHtmlExtension(path);
                    OutputStream os = context.getOutputStream();

                    String htmlHeaderLocal = fileConfig.getString("htmlHeader", htmlHeader);
                    if (isHtml && MString.isSet(htmlHeaderLocal)) {
                        doTransform(context, new File(htmlHeaderLocal), fileConfig, null);
                    }

                    String transformType = fileConfig.getString("transform", null);
                    if (MString.isSet(transformType)) {
                        doTransform(context, file, fileConfig, transformType);
                    } else {
                        FileInputStream is = new FileInputStream(file);
                        MFile.copyFile(is, os);
                        is.close();
                    }

                    String htmlFooterLocal = fileConfig.getString("htmlFooter", htmlFooter);
                    if (isHtml && MString.isSet(htmlFooterLocal)) {
                        doTransform(context, new File(htmlFooterLocal), fileConfig, null);
                    }

                    os.flush();

                } catch (Throwable t) {
                    log().w("get failed", file, t);
                    sendError(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null);
                }
                return;
            }
        }

        String orgPath = path;
        // find template
        for (String extension : removeExtensions) {
            if (path.endsWith(extension)) {
                path = path.substring(0, path.length() - extension.length());
                break;
            }
        }

        for (String extension : extensionOrder) {
            String p = path + extension;
            file = new File(templateRoot, p);
            if (file.exists() && file.isFile()) {
                IReadProperties fileConfig = findConfig(file);
                prepareHead(context, file, orgPath, fileConfig);
                try {
                    doTransform(context, file, fileConfig, null);
                } catch (Throwable t) {
                    sendError(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, t);
                }
                return;
            }
        }
        log().d("file not found", path, file);
        sendError(context, HttpServletResponse.SC_NOT_FOUND, null);
    }

    @Override
    public IReadProperties findConfig(CallContext context) {
        String path = context.getHttpPath();
        path = MFile.normalizePath(path);
        File file = new File(templateRoot, path);
        String lowerPath = path.toLowerCase();
        // deny ?
        for (String extension : denyExtensions) {
            if (lowerPath.endsWith(extension)) {
                return null;
            }
        }
        if (file.exists() && file.isDirectory()) {
            path = path + "/" + index;
            file = new File(templateRoot, path);
        }
        if (file.exists()) {
            if (hasTransformExtension(lowerPath)) return null;
            return findConfig(file);
        }
        for (String extension : removeExtensions) {
            if (path.endsWith(extension)) {
                path = path.substring(0, path.length() - extension.length());
                break;
            }
        }
        for (String extension : extensionOrder) {
            String p = path + extension;
            file = new File(templateRoot, p);
            if (file.exists() && file.isFile()) {
                return findConfig(file);
            }
        }

        return null;
    }

    private IReadProperties findConfig(File file) {
        File cfgFile = new File(file + cfgExtension);
        if (cfgFile.exists()) {
            MProperties out = cfgCache.get(cfgFile);
            if (out == null
                    || out.getLong("_cfg_modified", 0) != cfgFile.lastModified()
                    || out.getLong("_cfg_size", 0) != cfgFile.length()) {
                log().d("Load file config", cfgFile);
                out = MProperties.load(cfgFile);
                out.setLong("_modified", cfgFile.lastModified());
                out.setLong("_size", cfgFile.length());
                cfgCache.put(cfgFile, out);
            }
            return out;
        }
        return cfgDefault;
    }

    public boolean hasTransformExtension(String path) {
        for (String extension : extensionOrder) {
            if (path.endsWith(extension)) return true;
        }
        return false;
    }

    public boolean hasHtmlExtension(String path) {
        for (String extension : htmlExtensions) {
            if (path.endsWith(extension)) return true;
        }
        return false;
    }

    /**
     * Transform file into response.
     *
     * @param context
     * @param from
     * @throws Exception
     */
    protected void doTransform(CallContext context, File from, IReadProperties cfg, String type)
            throws Exception {

        MProperties param = new MProperties(environment);
        param.put("stamp", stamp);
        param.put("session", context.getSession().pub());
        param.put("sessionId", context.getSessionId());
        param.put("request", context.getHttpRequest().getParameterMap());
        param.put("path", context.getHttpPath());
        if (csrfEnabled) param.put("csrfToken", CherryWebUtil.createCsrfToken(context));

        doFillParamsForTransform(context, from, type, param);

        OutputStream os = context.getOutputStream();
        TransformUtil.transform(from, os, getDocumentRoot(), null, null, param, type);
        os.flush();
    }

    /**
     * Overwrite the method to set additional parameters before transformation.
     *
     * @param context
     * @param from
     * @param type
     * @param param
     */
    protected void doFillParamsForTransform(
            CallContext context, File from, String type, MProperties param) {}

    protected void prepareHead(
            CallContext context, File from, String path, IReadProperties fileConfig) {
        HttpServletResponse resp = context.getHttpResponse();
        resp.setCharacterEncoding(charsetEncoding);
        resp.setHeader("Last-Modified", MDate.toHttpHeaderDate(from.lastModified()));
        super.prepareHead(context, MFile.getFileExtension(from), path);
        if (fileConfig != null) {
            int cnt = 0;
            while (true) {
                String key = "httpHeader" + cnt;
                String value = fileConfig.getString(key, null);
                if (value == null) break;
                int pos = value.indexOf(':');
                if (pos > 0) {
                    resp.setHeader(value.substring(0, pos).trim(), value.substring(pos + 1).trim());
                }
                cnt++;
            }
        }
    }

    public File findTemplateFile(String path) {
        if (path.startsWith("/")) {
            if (MSystem.isWindows()) return new File(path.substring(1));
            else return new File(path);
        }
        return new File(getDocumentRoot(), path);
    }

    @Override
    public void sendError(CallContext context, int sc, Throwable t) {
        if (traceAccess)
            log().d(
                            name,
                            context.getHttpHost(),
                            "error",
                            context.getHttpRequest().getRemoteAddr(),
                            context.getHttpMethod(),
                            context.getHttpPath(),
                            sc);
        if (traceErrors) {
            if (t == null) {
                try {
                    throw new Exception();
                } catch (Exception ex) {
                    t = ex;
                }
            }
            log().d(name, context.getHttpHost(), sc, t);
        }
        if (context.getHttpResponse().isCommitted()) {
            log().w("Can't send error to committed content", name, sc);
            return;
        }

        if (errorTemplate != null) {

            try {
                context.getHttpResponse().setStatus(sc);
                MProperties param = new MProperties();
                param.put("stamp", stamp);
                param.put("session", context.getSession().pub());
                param.put("sessionId", context.getSessionId());
                param.put("request", context.getHttpRequest().getParameterMap());
                param.put("path", context.getHttpPath());
                if (csrfEnabled) param.put("csrfToken", CherryWebUtil.createCsrfToken(context));
                param.put("error", sc);
                param.put("errorMsg", MHttp.HTTP_STATUS_CODES.getOrDefault(sc, ""));

                ServletOutputStream os = context.getHttpResponse().getOutputStream();
                TransformUtil.transform(
                        errorTemplate, os, getDocumentRoot(), null, null, param, null);
                context.getHttpResponse().setContentType("text/html");
                context.getHttpResponse().setCharacterEncoding(charsetEncoding);
                os.flush();

            } catch (Throwable e) {
                log().e(name, errorTemplate, e);
            }
            return;
        }

        // fallback
        try {
            context.getHttpResponse().sendError(sc);
        } catch (IOException e) {
            log().t(e);
        }
    }

    @Override
    public File getTemplateRoot() {
        return templateRoot;
    }

    @Override
    public void doTransform(CallContext context, String template) throws Exception {
        template = MFile.normalizePath(template);
        File from = new File(templateRoot, template);
        IReadProperties cfg = findConfig(from);
        doTransform(context, from, cfg, null);
    }
}
