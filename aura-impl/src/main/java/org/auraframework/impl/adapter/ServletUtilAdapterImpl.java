/*
 * Copyright (C) 2013 salesforce.com, inc.
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

package org.auraframework.impl.adapter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.auraframework.adapter.*;
import org.auraframework.annotations.Annotations.ServiceComponent;
import org.auraframework.clientlibrary.ClientLibraryService;
import org.auraframework.def.*;
import org.auraframework.def.DefDescriptor.DefType;
import org.auraframework.http.CSP;
import org.auraframework.http.ManifestUtil;
import org.auraframework.impl.util.*;
import org.auraframework.impl.util.TemplateUtil.Script;
import org.auraframework.instance.InstanceStack;
import org.auraframework.service.*;
import org.auraframework.system.AuraContext;
import org.auraframework.system.AuraContext.Format;
import org.auraframework.system.AuraContext.Mode;
import org.auraframework.system.AuraResource;
import org.auraframework.throwable.*;
import org.auraframework.throwable.quickfix.DefinitionNotFoundException;
import org.auraframework.throwable.quickfix.QuickFixException;
import org.auraframework.util.AuraTextUtil;
import org.auraframework.util.json.JsonEncoder;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@ServiceComponent
public class ServletUtilAdapterImpl implements ServletUtilAdapter {
    private ContextService contextService;
    private ConfigAdapter configAdapter;
    private ExceptionAdapter exceptionAdapter;
    private SerializationService serializationService;
    private ClientLibraryService clientLibraryService;
    protected TemplateUtil templateUtil = new TemplateUtil();
    protected DefinitionService definitionService;
    protected ManifestUtil manifestUtil;

    /**
     * "Short" pages (such as manifest cookies and AuraFrameworkServlet pages) expire in 1 day.
     */
    protected static final long SHORT_EXPIRE_SECONDS = 24L * 60 * 60;
    protected static final long SHORT_EXPIRE = SHORT_EXPIRE_SECONDS * 1000;

    /**
     * "Long" pages (such as resources and cached HTML templates) expire in 45 days. We also use this to "pre-expire"
     * no-cache pages, setting their expiration a month and a half into the past for user agents that don't understand
     * Cache-Control: no-cache.
     * Same as auraBaseServlet.java
     */
    protected static final long LONG_EXPIRE = 45 * SHORT_EXPIRE;
    protected static final String UTF_ENCODING = "UTF-8";
    protected static final String HTML_CONTENT_TYPE = "text/html";
    protected static final String JAVASCRIPT_CONTENT_TYPE = "text/javascript";
    protected static final String MANIFEST_CONTENT_TYPE = "text/cache-manifest";
    protected static final String CSS_CONTENT_TYPE = "text/css";
    protected static final String SVG_CONTENT_TYPE = "image/svg+xml";

    /** Clickjack protection HTTP header */
    protected static final String HDR_FRAME_OPTIONS = "X-FRAME-OPTIONS";
    /** Baseline clickjack protection level for HDR_FRAME_OPTIONS header */
    protected static final String HDR_FRAME_SAMEORIGIN = "SAMEORIGIN";
    /** No-framing-at-all clickjack protection level for HDR_FRAME_OPTIONS header */
    protected static final String HDR_FRAME_DENY = "DENY";
    /** Limited access for HDR_FRAME_OPTIONS */
    protected static final String HDR_FRAME_ALLOWFROM = "ALLOW-FROM ";
    /**
     * Semi-standard HDR_FRAME_OPTIONS to have no restrictions.  Used because no
     * header at all is taken as an invitation for filters to add their own ideas.
     */
    protected static final String HDR_FRAME_ALLOWALL = "ALLOWALL";

    @PostConstruct
    public void createManifestUtil() {
        this.manifestUtil = new ManifestUtil(definitionService, contextService, configAdapter);
    }

    /**
     * Handle an exception in the servlet.
     *
     * This routine should be called whenever an exception has surfaced to the top level of the servlet. It should not be
     * overridden unless Aura is entirely subsumed. Most special cases can be handled by the Aura user by implementing
     * {@link ExceptionAdapter ExceptionAdapter}.
     *
     * @param t the throwable to write out.
     * @param quickfix is this exception a valid quick-fix
     * @param context the aura context.
     * @param request the request.
     * @param response the response.
     * @param written true if we have started writing to the output stream.
     * @throws IOException if the output stream does.
     * @throws ServletException if send404 does (should not generally happen).
     */
    @Override
    public void handleServletException(Throwable t, boolean quickfix, AuraContext context,
            HttpServletRequest request, HttpServletResponse response,
            boolean written) throws IOException {
        try {
            Throwable mappedEx = t;
            boolean map = !quickfix;
            Format format = context.getFormat();

            //
            // This seems to fail, though the documentation implies that you can do
            // it.
            //
            // if (written && !response.isCommitted()) {
            // response.resetBuffer();
            // written = false;
            // }
            if (!written) {
                // Should we only delete for JSON?
                setNoCache(response);
            }
            if (mappedEx instanceof IOException) {
                //
                // Just re-throw IOExceptions.
                //
                throw (IOException) mappedEx;
            } else if (mappedEx instanceof NoAccessException) {
                Throwable cause = mappedEx.getCause();
                String denyMessage = mappedEx.getMessage();

                map = false;
                if (cause != null) {
                    //
                    // Note that the exception handler can remap the cause here.
                    //
                    cause = exceptionAdapter.handleException(cause);
                    denyMessage += ": cause = " + cause.getMessage();
                }
                //
                // Is this correct?!?!?!
                //
                if (format != Format.JSON) {
                    this.send404(request.getServletContext(), request, response);
                    if (!isProductionMode(context.getMode())) {
                        // Preserve new lines and tabs in the stacktrace since this is directly being written on to the
                        // page
                        denyMessage = "<pre>" + AuraTextUtil.escapeForHTML(denyMessage) + "</pre>";
                        response.getWriter().println(denyMessage);
                    }
                    return;
                }
            } else if (mappedEx instanceof QuickFixException) {
                if (isProductionMode(context.getMode())) {
                    //
                    // In production environments, we want wrap the quick-fix. But be a little careful here.
                    // We should never mark the top level as a quick-fix, because that means that we gack
                    // on every mis-spelled app. In this case we simply send a 404 and bolt.
                    //
                    if (mappedEx instanceof DefinitionNotFoundException) {
                        DefinitionNotFoundException dnfe = (DefinitionNotFoundException) mappedEx;

                        if (dnfe.getDescriptor() != null && dnfe.getDescriptor().equals(context.getApplicationDescriptor())) {
                            // We're in production and tried to hit an aura app that doesn't exist.
                            // just show the standard 404 page.
                            this.send404(request.getServletContext(), request, response);
                            return;
                        }
                    }
                    map = true;
                    mappedEx = new AuraUnhandledException("404 Not Found (Application Error)", mappedEx);
                }
            }
            if (map) {
                mappedEx = exceptionAdapter.handleException(mappedEx);
            }

            PrintWriter out = response.getWriter();

            //
            // If we have written out data, We are kinda toast in this case.
            // We really want to roll it all back, but we can't, so we opt
            // for the best we can do. For HTML we can do nothing at all.
            //
            if (format == Format.JSON) {
                if (!written) {
                    out.write(CSRF_PROTECT);
                }
                //
                // If an exception happened while we were emitting JSON, we want the
                // client to ignore the now-corrupt data structure. 404s and 500s
                // cause the client to prepend /*, so we can effectively erase the
                // bad data by appending a */ here and then serializing the exception
                // info.
                //
                out.write("*/");
                //
                // Unfortunately we can't do the following now. It might be possible
                // in some cases, but we don't want to go there unless we have to.
                //
            }
            if (format == Format.JS) {
                // send 500 by default
                int jsStatus = HttpStatus.SC_INTERNAL_SERVER_ERROR;
                if (!manifestUtil.isManifestEnabled()) {
                    // if browser applicationCache is disabled then send 200 with javascript exception code below
                    jsStatus = HttpStatus.SC_OK;
                    // make sure error response is NOT cached
                    setNoCache(response);
                }
                response.setStatus(jsStatus);
            } else if(format == Format.CSS) {
                response.setStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            }
            if (format == Format.JSON || format == Format.HTML || format == Format.JS || format == Format.CSS) {
                //
                // We only write out exceptions for HTML or JSON.
                // Seems bogus, but here it is.
                //
                // Start out by cleaning out some settings to ensure we don't
                // check too many things, leading to a circular failure. Note
                // that this is still a bit dangerous, as we seem to have a lot
                // of magic in the serializer.
                //
                // Clear the InstanceStack before trying to serialize the exception since the Throwable has likely
                // rendered the stack inaccurate, and may falsely trigger NoAccessExceptions.
                InstanceStack stack = context.getInstanceStack();
                List<String> list = stack.getStackInfo();
                for (int count = list.size(); count > 0; count--) {
                    stack.popInstance(stack.peek());
                }

                serializationService.write(mappedEx, null, out);
                if (format == Format.JSON) {
                    out.write("/*ERROR*/");
                }
            }
        } catch (IOException ioe) {
            throw ioe;
        } catch (Throwable death) {
            //
            // Catch any other exception and log it. This is actually kinda bad, because something has
            // gone horribly wrong. We should write out some sort of generic page other than a 404,
            // but at this point, it is unclear what we can do, as stuff is breaking right and left.
            //
            try {
                response.setStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR);
                exceptionAdapter.handleException(death);
                if (!isProductionMode(context.getMode())) {
                    response.getWriter().println(death.getMessage());
                }
            } catch (IOException ioe) {
                throw ioe;
            } catch (Throwable doubleDeath) {
                // we are totally hosed.
                if (!isProductionMode(context.getMode())) {
                    response.getWriter().println(doubleDeath.getMessage());
                }
            }
        } finally {
            this.contextService.endContext();
        }
    }

    @Override
    public void send404(ServletContext servletContext, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.getWriter().println("404 Not Found"
                + "<!-- Extra text so IE will display our custom 404 page -->"
                + "<!--                                                   -->"
                + "<!--                                                   -->"
                + "<!--                                                   -->"
                + "<!--                                                   -->"
                + "<!--                                                   -->"
                + "<!--                                                   -->"
                + "<!--                                                   -->"
                + "<!--                                                   -->");
        this.contextService.endContext();
    }

    @Override
    public List<String> getScripts(AuraContext context, boolean safeInlineJs, boolean ignoreNonCacheableScripts, Map<String,Object> attributes)
            throws QuickFixException {
        List<String> ret = Lists.newArrayList();
        // Client libraries
        ret.addAll(getJsClientLibraryUrls(context));
        ret.addAll(getBaseScripts(context, attributes));
        ret.addAll(getFrameworkScripts(context, safeInlineJs, ignoreNonCacheableScripts, attributes));
        return ret;
    }

    @Override
    public List<String> getStyles(AuraContext context) throws QuickFixException {
        Set<String> ret = Sets.newLinkedHashSet();

        // Add css client libraries
        ret.addAll(getCssClientLibraryUrls(context));
        ret.add(getAppCssUrl(context));

        return new ArrayList<>(ret);
    }

    /**
     * Gets all client libraries specified. Uses client library service to resolve any urls that weren't specified.
     * Returns list of non empty client library urls.
     *
     *
     * @param context aura context
     * @param type CSS or JS
     * @return list of urls for client libraries
     */
    private List<String> getClientLibraryUrls(AuraContext context, ClientLibraryDef.Type type)
            throws QuickFixException {
    	return new ArrayList<>(clientLibraryService.getUrls(context, type));
    }

    /**
     * Get the set of base scripts for a context.
     */
    @Override
    public List<String> getBaseScripts(AuraContext context, Map<String,Object> attributes) throws QuickFixException {
        Set<String> ret = Sets.newLinkedHashSet();

        // Aura framework
        ret.add(getFrameworkUrl());

        return new ArrayList<>(ret);
    }

    @Override
    public List<String> getFrameworkScripts(AuraContext context, boolean safeInlineJs, boolean ignoreNonCacheableScripts, Map<String,Object> attributes)
        throws QuickFixException {
        List<String> ret = Lists.newArrayList();

        if (safeInlineJs && !ignoreNonCacheableScripts) {
            ret.add(getInlineJsUrl(context, attributes));
        }

        ret.add(getAppJsUrl(context, null));

        if (!ignoreNonCacheableScripts) {
            ret.add(getBootstrapUrl(context, attributes));
        }


        return ret;
    }

    @Override
    public List<String> getFrameworkFallbackScripts(AuraContext context, boolean safeInlineJs, Map<String,Object> attributes)
        throws QuickFixException {
        List<String> ret = Lists.newArrayList();
        // appcache fallback can only use for items NOT listed in the CACHE section of the manifest
        ret.add(getBootstrapUrl(context, attributes) + " " + getBootstrapFallbackUrl(context, attributes));
        return ret;
    }

    @Override
    public List <String> getJsClientLibraryUrls (AuraContext context) throws QuickFixException {
    	return getClientLibraryUrls(context, ClientLibraryDef.Type.JS);
    }

    @Override
    public void writeScriptUrls(AuraContext context, Map<String, Object> componentAttributes, StringBuilder sb) throws QuickFixException, IOException {
        templateUtil.writeHtmlScripts(context, this.getJsClientLibraryUrls(context), Script.LAZY, sb);
        templateUtil.writeHtmlScript(context, this.getInlineJsUrl(context, componentAttributes), Script.SYNC, sb);
        templateUtil.writeHtmlScript(context, this.getFrameworkUrl(), Script.SYNC, sb);
        templateUtil.writeHtmlScript(context, this.getAppJsUrl(context, null), Script.SYNC, sb);
        templateUtil.writeHtmlScript(context, this.getBootstrapUrl(context, componentAttributes), Script.SYNC, sb);
    }

    @Override
    public List <String> getCssClientLibraryUrls (AuraContext context) throws QuickFixException {
    	return new ArrayList<>(getClientLibraryUrls(context, ClientLibraryDef.Type.CSS));
    }

    @Override
    public String getFrameworkUrl() {
        return configAdapter.getAuraJSURL();
    }


    private void addAttributes(StringBuilder builder, Map<String,Object> attributes) {
        //
        // This feels a lot like a hack.
        //
        if (attributes != null && !attributes.isEmpty()) {
            builder.append("?aura.attributes=");
            builder.append(AuraTextUtil.urlencode(JsonEncoder.serialize(attributes, false)));
        }
    }
    /**
     * Get the set of base scripts for a context.
     */
    @Override
    public String getBootstrapUrl(AuraContext context, Map<String,Object> attributes) {
        return commonJsUrl("/bootstrap.js", context, attributes);
    }

    @Override
    public String getBootstrapFallbackUrl(AuraContext context, Map<String,Object> attributes) {
        String contextPath = context.getContextPath();
        // not including nonce in fallback url to force no cache headers
        return String.format("%s/auraFW/resources/aura/fallback/fallback.bootstrap.js", contextPath);
    }

    @Override
    public String getInlineJsUrl(AuraContext context, Map<String,Object> attributes) {
        return commonJsUrl("/inline.js", context, attributes);
    }

    @Override
    public String getAppJsUrl(AuraContext context, Map<String,Object> attributes) {
        return commonJsUrl("/app.js", context, attributes);
    }

    @Override
    public String getAppCssUrl(AuraContext context) {
        String contextPath = context.getContextPath();
        StringBuilder defs = new StringBuilder(contextPath).append("/l/");
        defs.append(context.getEncodedURL(AuraContext.EncodingStyle.Css));
        defs.append("/app.css");
        return defs.toString();
    }

    private String commonJsUrl (String filepath, AuraContext context, Map<String,Object> attributes) {
        StringBuilder url = new StringBuilder(context.getContextPath()).append("/l/");
        url.append(context.getEncodedURL(AuraContext.EncodingStyle.Normal));
        url.append(filepath);
        if (attributes != null) {
            addAttributes(url, attributes);
        }
        return url.toString();
    }

    /**
     * Tell the browser to not cache.
     *
     * This sets several headers to try to ensure that the page will not be cached. Not sure if last modified matters
     * -goliver
     *
     * @param response the HTTP response to which we will add headers.
     */
    @Override
    public void setNoCache(HttpServletResponse response) {
        long past = System.currentTimeMillis() - LONG_EXPIRE;
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, past);
        response.setDateHeader(HttpHeaders.LAST_MODIFIED, past);
    }

    /**
     * Check to see if we are in production mode.
     */
    @Override
    public boolean isProductionMode(Mode mode) {
        return mode == Mode.PROD || configAdapter.isProduction();
    }

    /**
     * Sets mandatory headers, notably for anti-clickjacking.
     */
    @Override
    public void setCSPHeaders(DefDescriptor<?> top, HttpServletRequest req, HttpServletResponse rsp) {

        if(canSkipCSPHeader(top, req)) {
            return;
        }

        ContentSecurityPolicy csp = configAdapter.getContentSecurityPolicy(
                top == null ? null : top.getQualifiedName(), req);

        if (csp != null) {
            rsp.setHeader(CSP.Header.SECURE, csp.getCspHeaderValue());
            Collection<String> terms = csp.getFrameAncestors();
            if (terms != null) {
                // not open to the world; figure whether we can express an X-FRAME-OPTIONS header:
                if (terms.size() == 0) {
                    // closed to any framing at all
                    rsp.setHeader(HDR_FRAME_OPTIONS, HDR_FRAME_DENY);
                } else if (terms.size() == 1) {
                    // With one ancestor term, we're either SAMEORIGIN or ALLOWFROM
                    for (String site : terms) {
                        if (site == null) {
                            // Add same-origin headers and policy terms
                            rsp.addHeader(HDR_FRAME_OPTIONS, HDR_FRAME_SAMEORIGIN);
                        } else if (!site.contains("*") && !site.matches("^[a-z]+:$")) {
                            // XFO can't express wildcards or protocol-only, so set only for a specific site:
                            rsp.addHeader(HDR_FRAME_OPTIONS, HDR_FRAME_ALLOWFROM + site);
                        } else {
                            // When XFO can't express it, still set an ALLOWALL so filters don't jump in
                            rsp.addHeader(HDR_FRAME_OPTIONS, HDR_FRAME_ALLOWALL);
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if CSP Header setting is already inherited from one.app (top level context)
     * See https://www.w3.org/TR/CSP2/#which-policy-applies
     * @param defDesc
     * @param req
     * @return true if CSP header setting can be skipped
     */
    private boolean canSkipCSPHeader(final DefDescriptor<?> defDesc, final HttpServletRequest req) {
        if(defDesc == null | req == null) {
            return false;
        }

        // CSP inheritance is supported starting from CSP2
        if(!isCSP2Supported(req)) {
            return false;
        }

        final String descriptorName = defDesc.getDescriptorName();
        if(!descriptorName.equals("one:one") && !descriptorName.equals("clients:msMail")) { // only skip while loading one.app or msMail.app
            return false;
        }

        final String auraFormat = req.getParameter("aura.format");
        if(auraFormat != null && auraFormat.equals("HTML")) {
            return false;
        }

        // Skip one.app requests for non HTML content with already established aura context
        final String auraContext = req.getParameter("aura.context");
        if(auraContext != null) {
            return true;
        }

        return false;
    }

    /**
     * Check if Content Security Policy Level 2 is supported by the browser
     * Currently, IE, Edge and Opera Mini browsers don't support CSP2 as per http://caniuse.com/#feat=contentsecuritypolicy2
     * @return true if user agent used in req supports CSP2
     */
    private boolean isCSP2Supported(final HttpServletRequest req) {
        final String userAgent = req.getHeader("User-Agent");
        if(userAgent == null) {
            return false;
        }

        final int browser = BrowserUserAgent.parseBrowser(userAgent);
        if(UserAgent.IE.match(browser)) { // UserAgent.IE is used for IE11 and IE12 (Edge)
            return false;
        }

        return true;
    }

    /**
     * Set a long cache timeout.
     *
     * This sets several headers to try to ensure that the page will be cached for a reasonable length of time. Of note
     * is the last-modified header, which is set to a day ago so that browsers consider it to be safe.
     *
     * @param response the HTTP response to which we will add headers.
     */
    @Override
    public void setLongCache(HttpServletResponse response) {
        this.setCacheTimeout(response, LONG_EXPIRE);
    }

    /**
     * Set a 'short' cache timeout.
     *
     * This sets several headers to try to ensure that the page will be cached for a shortish length of time. Of note is
     * the last-modified header, which is set to a day ago so that browsers consider it to be safe.
     *
     * @param response the HTTP response to which we will add headers.
     */
    @Override
    public void setShortCache(HttpServletResponse response) {
        this.setCacheTimeout(response, SHORT_EXPIRE);
    }

    /**
     * Sets cache timeout to a given value.
     *
     * This sets several headers to try to ensure that the page will be cached for the given length of time. Of note is
     * the last-modified header, which is set to a day ago so that browsers consider it to be safe.
     *
     * @param response the HTTP response to which we will add headers.
     * @param expiration timeout value in milliseconds.
     */
    @Override
    public void setCacheTimeout(HttpServletResponse response, long expiration) {
        long now = System.currentTimeMillis();
        response.setHeader(HttpHeaders.VARY, "Accept-Encoding");
        response.setHeader(HttpHeaders.CACHE_CONTROL, String.format("max-age=%s, public", expiration / 1000));
        response.setDateHeader(HttpHeaders.EXPIRES, now + expiration);
        response.setDateHeader(HttpHeaders.LAST_MODIFIED, now - SHORT_EXPIRE);
    }

    @Override
    public String getContentType(AuraContext.Format format) {
        switch (format) {
        case MANIFEST:
            return MANIFEST_CONTENT_TYPE;
        case CSS:
            return CSS_CONTENT_TYPE;
        case JS:
            return JAVASCRIPT_CONTENT_TYPE;
        case JSON:
            return JsonEncoder.MIME_TYPE;
        case HTML:
            return HTML_CONTENT_TYPE;
        case SVG:
            return SVG_CONTENT_TYPE;
        default:
        }
        return ("text/plain");
    }

    @Override
    public boolean isValidDefType(DefType defType, Mode mode) {
        return (defType == DefType.APPLICATION || defType == DefType.COMPONENT);
    }

    @Override
    public boolean resourceServletGetPre(HttpServletRequest request, HttpServletResponse response, AuraResource resource) {
        return false;
    }

    @Override
    public boolean actionServletGetPre(HttpServletRequest request, HttpServletResponse response) throws IOException {
        return false;
    }

    @Override
    public boolean actionServletPostPre(HttpServletRequest request, HttpServletResponse response) throws IOException {
        return false;
    }

    /**
     * check the top level component/app and get dependencies.
     *
     * This routine checks to see that we have a valid top level component. If our top level component is out of sync,
     * we have to ignore it here, but we _must_ force the client to not cache the response.
     *
     * If there is a QFE, we substitute the QFE descriptor for the one given us, and continue. Again, we cannot allow
     * caching.
     *
     * Finally, if there is no descriptor given, we simply ignore the request and give them an empty response. Which is
     * done here by returning null.
     *
     * Also note that this handles the 'if-modified-since' header, as we want to tell the browser that nothing changed
     * in that case.
     *
     * @param request the request (for exception handling)
     * @param response the response (for exception handling)
     * @param context the context to get the definition.
     * @return the set of descriptors we are sending back, or null in the case that we handled the response.
     * @throws IOException if there was an IO exception handling a client out of sync exception
     * @throws ServletException if there was a problem handling the out of sync
     */
    @Override
    public Set<DefDescriptor<?>> verifyTopLevel(HttpServletRequest request, HttpServletResponse response,
            AuraContext context) throws IOException {
        DefDescriptor<? extends BaseComponentDef> appDesc = context.getApplicationDescriptor();

        context.setPreloading(true);
        if (appDesc == null) {
            //
            // This means we have nothing to say to the client, so the response is
            // left completely empty.
            //
            return null;
        }
        long ifModifiedSince = request.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE);
        String uid = context.getUid(appDesc);
        try {
            try {
                definitionService.updateLoaded(appDesc);
                if (uid != null && ifModifiedSince != -1) {
                    //
                    // In this case, we have an unmodified descriptor, so just tell
                    // the client that.
                    //
                    response.sendError(HttpServletResponse.SC_NOT_MODIFIED);
                    return null;
                }
            } catch (ClientOutOfSyncException coose) {
                //
                // We can't actually handle an out of sync here, since we are doing a
                // resource load. We have to ignore it, and continue as if nothing happened.
                // But in the process, we make sure to set 'no-cache' so that the result
                // is thrown away. This may actually not give the right result in bizarre
                // corner cases... beware cache inconsistencies on revert after a QFE.
                //
                // We actually probably should do something different, like send a minimalist
                // set of stuff to make the client re-try.
                //
                this.setNoCache(response);
                String oosUid = definitionService.getUid(null, appDesc);
                return definitionService.getDependencies(oosUid);
            }
        } catch (QuickFixException qfe) {
            //
            // A quickfix exception means that we couldn't compile something.
            // In this case, we still want to preload things, but we want to preload
            // quick fix values, note that we force NoCache here.
            //
            this.setNoCache(response);
            this.handleServletException(qfe, true, context, request, response, true);
            return null;
        }
        this.setLongCache(response);
        if (uid == null) {
            uid = context.getUid(appDesc);
        }
        return definitionService.getDependencies(uid);
    }

    /**
     * get the manifest URL.
     *
     * This routine will simply return the string, it does not check to see if the manifest is
     * enabled first.
     *
     * @return a string for the manifest URL.
     */
    @Override
    public String getManifestUrl(AuraContext context, Map<String,Object> attributes) {
        String contextPath = context.getContextPath();
        String ret = "";

        StringBuilder defs = new StringBuilder(contextPath).append("/l/");
        defs.append(context.getEncodedURL(AuraContext.EncodingStyle.Bare));
        defs.append("/app.manifest");
        addAttributes(defs, attributes);
        ret = defs.toString();
        return ret;
    }

    /**
     * Checks current framework UID to one in provided AuraContext.
     * Throws ClientOutOfSyncException if they don't match.
     *
     * @param context AuraContext
     * @throws ClientOutOfSyncException
     */
    @Override
    public void checkFrameworkUID(AuraContext context) throws ClientOutOfSyncException {
        String fwUID = configAdapter.getAuraFrameworkNonce();
        String ctxUID = context.getFrameworkUID();
        if (!fwUID.equals(ctxUID)) {
            throw new ClientOutOfSyncException("Framework UID mismatch. Expected: " + fwUID +
                    " Actual: " + ctxUID);
        }
    }

    /**
     * @param definitionService the definitionService to set
     */
    @Inject
    public void setDefinitionService(DefinitionService definitionService) {
        this.definitionService = definitionService;
    }

    /**
     * Injection override.
     */
    @Inject
    public void setContextService(ContextService contextService) {
        this.contextService = contextService;
    }

    /**
     * Injection override.
     */
    @Inject
    public void setConfigAdapter(ConfigAdapter configAdapter) {
        this.configAdapter = configAdapter;
    }

    /**
     * Injection override.
     */
    @Inject
    public void setExceptionAdapter(ExceptionAdapter exceptionAdapter) {
        this.exceptionAdapter = exceptionAdapter;
    }

    /**
     * Injection override.
     */
    @Inject
    public void setSerializationService(SerializationService serializationService) {
        this.serializationService = serializationService;
    }

    /**
     * Injection override.
     */
    @Inject
    public void setClientLibraryService(ClientLibraryService clientLibraryService) {
        this.clientLibraryService = clientLibraryService;
    }

    /**
     * Exposed for testing
     */
    public void setManifestUtil(ManifestUtil manifestUtil) {
        this.manifestUtil = manifestUtil;
    }
}
