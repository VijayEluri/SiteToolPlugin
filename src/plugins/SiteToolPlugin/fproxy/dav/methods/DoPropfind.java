/*
 * Copyright 1999,2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package plugins.SiteToolPlugin.fproxy.dav.methods;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import plugins.SiteToolPlugin.fproxy.dav.api.ILockedObject;
import plugins.SiteToolPlugin.fproxy.dav.api.IMimeTyper;
import plugins.SiteToolPlugin.fproxy.dav.api.IResourceLocks;
import plugins.SiteToolPlugin.fproxy.dav.api.IStoredObject;
import plugins.SiteToolPlugin.fproxy.dav.api.ITransaction;
import plugins.SiteToolPlugin.fproxy.dav.api.IWebDAVStore;
import plugins.SiteToolPlugin.fproxy.dav.api.WebDAVStatus;
import plugins.SiteToolPlugin.fproxy.dav.exceptions.AccessDeniedException;
import plugins.SiteToolPlugin.fproxy.dav.exceptions.LockFailedException;
import plugins.SiteToolPlugin.fproxy.dav.exceptions.WebDAVException;
import plugins.SiteToolPlugin.fproxy.dav.util.XMLHelper;
import plugins.SiteToolPlugin.fproxy.dav.util.XMLWriter;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

public class DoPropfind extends AbstractMethod {

	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(DoPropfind.class);
	}

	enum FindType {
		BY_PROPERTY, // Specify a property mask.
		ALL, // Display all properties.
		NAMES //Return property names.
	}

    private IWebDAVStore _store;
    private IResourceLocks _resourceLocks;
    private IMimeTyper _mimeTyper;

    private int _depth;

    public DoPropfind(Toadlet parent, IWebDAVStore store, IResourceLocks resLocks,
            IMimeTyper mimeTyper) {
    	super(parent);
        _store = store;
        _resourceLocks = resLocks;
        _mimeTyper = mimeTyper;
    }

	public void handle(ITransaction transaction, URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException, LockFailedException {
		if (logDEBUG)
			Logger.debug(this, "-- " + this.getClass().getName());

        // Retrieve the resources
        String path = getCleanPath(getRelativePath(req));
        String tempLockOwner = "doPropfind" + System.currentTimeMillis() + req.toString();
        _depth = getDepth(req);

        if (_resourceLocks.lock(transaction, path, tempLockOwner, false,
                _depth, TEMP_TIMEOUT, TEMPORARY)) {

            IStoredObject so = null;
            try {
                so = _store.getStoredObject(transaction, path);
                if (so == null) {
                    //resp.setContentType("text/xml; charset=UTF-8");
                    //resp.sendError(WebDAVStatus.SC_NOT_FOUND, req.getRequestURI());
                	sendError(WebDAVStatus.SC_NOT_FOUND, ctx);
                    return;
                }

                Vector<String> properties = null;
                path = getCleanPath(getRelativePath(req));

                FindType propertyFindType = FindType.ALL;
                Node propNode = null;

                if (req.getContentLength() != 0) {
                    try {
                    	DocumentBuilder documentBuilder = getDocumentBuilder();
        				Document document = documentBuilder.parse(new InputSource(req.getRawData().getInputStream()));
        				// Get the root element of the document
        				Element rootElement = document.getDocumentElement();

                        propNode = XMLHelper.findSubElement(rootElement, "prop");
                        if (propNode != null) {
                            propertyFindType = FindType.BY_PROPERTY;
                        } else if (XMLHelper.findSubElement(rootElement, "propname") != null) {
                            propertyFindType = FindType.NAMES;
                        } else if (XMLHelper.findSubElement(rootElement, "allprop") != null) {
                            propertyFindType = FindType.ALL;
                        }
                    } catch (Exception e) {
                    	Logger.error(this, "FIXME", e);
                        sendError(WebDAVStatus.SC_INTERNAL_SERVER_ERROR, ctx);
                        return;
                    }
                } else {
                    // no content, which means it is a allprop request
                    propertyFindType = FindType.ALL;
                }

                HashMap<String, String> namespaces = new HashMap<String, String>();
                namespaces.put("DAV:", "D");

                if (propertyFindType == FindType.BY_PROPERTY) {
                    properties = XMLHelper.getPropertiesFromXML(propNode);
                }

                Bucket xmlBucket = ctx.getBucketFactory().makeBucket(-1);
        		OutputStream os = xmlBucket.getOutputStream();
        		XMLWriter xmlWriter = new XMLWriter(os, namespaces);
        		
                xmlWriter.writeXMLHeader();
                xmlWriter.writeElement("DAV::multistatus", XMLWriter.OPENING);
                if (_depth == 0) {
                    parseProperties(transaction, req, xmlWriter, path, propertyFindType, properties, _mimeTyper.getMimeType(path));
                } else {
                    recursiveParseProperties(transaction, path, req, xmlWriter, propertyFindType, properties, _depth, _mimeTyper.getMimeType(path));
                }
                xmlWriter.writeElement("DAV::multistatus", XMLWriter.CLOSING);

                xmlWriter.close();
                os.close();

                sendMultiStatus(xmlBucket, null, ctx);

            } catch (AccessDeniedException e) {
                sendError(WebDAVStatus.SC_FORBIDDEN, ctx);
            } catch (WebDAVException e) {
                Logger.error(this, "Sending internal error!", e);
                sendError(WebDAVStatus.SC_INTERNAL_SERVER_ERROR, ctx);
            } finally {
                _resourceLocks.unlockTemporaryLockedObjects(transaction, path,
                        tempLockOwner);
            }
        } else {
            Hashtable<String, Integer> errorList = new Hashtable<String, Integer>();
            errorList.put(path, WebDAVStatus.SC_LOCKED);
            sendReport(req, errorList, null, ctx);
        }
    }

    /**
     * goes recursive through all folders. used by propfind
     * 
     * @param currentPath
     *      the current path
     * @param req
     *      HTTPRequest
     * @param xmlWriter
     * @param propertyFindType
     * @param properties
     * @param depth
     *      depth of the propfind
     * @throws IOException 
     * @throws IOException
     *      if an error in the underlying store occurs
     */
    private void recursiveParseProperties(ITransaction transaction,
            String currentPath, HTTPRequest req, XMLWriter xmlWriter,
            FindType propertyFindType, Vector<String> properties, int depth,
            String mimeType) throws WebDAVException, IOException {

        parseProperties(transaction, req, xmlWriter, currentPath,
                propertyFindType, properties, mimeType);

        if (depth > 0) {
            // no need to get name if depth is already zero
            String[] names = _store.getChildrenNames(transaction, currentPath);
            names = names == null ? new String[] {} : names;
            String newPath = null;

            for (String name : names) {
                newPath = currentPath;
                if (!(newPath.endsWith("/"))) {
                    newPath += "/";
                }
                newPath += name;
                recursiveParseProperties(transaction, newPath, req,
                        xmlWriter, propertyFindType, properties, depth - 1,
                        mimeType);
            }
        }
    }

    /**
     * Propfind helper method.
     * 
     * @param req
     *      The servlet request
     * @param xmlWriter
     *      XML response to the Propfind request
     * @param path
     *      Path of the current resource
     * @param propertyFindType
     *      Propfind type
     * @param propertiesVector
     *      If the propfind type is find properties by name, then this Vector
     *      contains those properties
     * @throws IOException 
     */
    private void parseProperties(ITransaction transaction,
            HTTPRequest req, XMLWriter xmlWriter, String path,
            FindType propertyFindType, Vector<String> propertiesVector, String mimeType)
            throws WebDAVException, IOException {

        IStoredObject so = _store.getStoredObject(transaction, path);

        boolean isFolder = so.isFolder();
        String creationdate = CREATION_DATE_FORMAT.format(so.getCreationDate());
        String lastModified = LAST_MODIFIED_DATE_FORMAT.format(so
                .getLastModified());
        String resourceLength = String.valueOf(so.getResourceLength());

        // ResourceInfo resourceInfo = new ResourceInfo(path, resources);

        xmlWriter.writeElement("DAV::response", XMLWriter.OPENING);
        String status = new String("HTTP/1.1 " + WebDAVStatus.SC_OK + " "
                + WebDAVStatus.getStatusText(WebDAVStatus.SC_OK));

        // Generating href element
        xmlWriter.writeElement("DAV::href", XMLWriter.OPENING);

        String href = req.getPath();
        String servletPath = req.getPath();
        if (servletPath != null) {
            if ((href.endsWith("/")) && (servletPath.startsWith("/")))
                href += servletPath.substring(1);
            else
                href += servletPath;
        }
        if ((href.endsWith("/")) && (path.startsWith("/")))
            href += path.substring(1);
        else
            href += path;
        if ((isFolder) && (!href.endsWith("/")))
            href += "/";

        xmlWriter.writeText(rewriteUrl(href));

        xmlWriter.writeElement("DAV::href", XMLWriter.CLOSING);

        String resourceName = path;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash != -1)
            resourceName = resourceName.substring(lastSlash + 1);

        switch (propertyFindType) {

        case ALL:

            xmlWriter.writeElement("DAV::propstat", XMLWriter.OPENING);
            xmlWriter.writeElement("DAV::prop", XMLWriter.OPENING);

            xmlWriter.writeProperty("DAV::creationdate", creationdate);
            xmlWriter.writeElement("DAV::displayname", XMLWriter.OPENING);
            xmlWriter.writeData(resourceName);
            xmlWriter.writeElement("DAV::displayname", XMLWriter.CLOSING);
            if (!isFolder) {
                xmlWriter
                        .writeProperty("DAV::getlastmodified", lastModified);
                xmlWriter.writeProperty("DAV::getcontentlength",
                        resourceLength);
                String contentType = mimeType;
                if (contentType != null) {
                    xmlWriter.writeProperty("DAV::getcontenttype",
                            contentType);
                }
                xmlWriter.writeProperty("DAV::getetag", getETag(so));
                xmlWriter.writeElement("DAV::resourcetype",
                        XMLWriter.NO_CONTENT);
            } else {
                xmlWriter.writeElement("DAV::resourcetype",
                        XMLWriter.OPENING);
                xmlWriter.writeElement("DAV::collection",
                        XMLWriter.NO_CONTENT);
                xmlWriter.writeElement("DAV::resourcetype",
                        XMLWriter.CLOSING);
            }

            writeSupportedLockElements(transaction, xmlWriter, path);

            writeLockDiscoveryElements(transaction, xmlWriter, path);

            xmlWriter.writeProperty("DAV::source", "");
            xmlWriter.writeElement("DAV::prop", XMLWriter.CLOSING);
            xmlWriter.writeElement("DAV::status", XMLWriter.OPENING);
            xmlWriter.writeText(status);
            xmlWriter.writeElement("DAV::status", XMLWriter.CLOSING);
            xmlWriter.writeElement("DAV::propstat", XMLWriter.CLOSING);

            break;

        case NAMES:

            xmlWriter.writeElement("DAV::propstat", XMLWriter.OPENING);
            xmlWriter.writeElement("DAV::prop", XMLWriter.OPENING);

            xmlWriter
                    .writeElement("DAV::creationdate", XMLWriter.NO_CONTENT);
            xmlWriter.writeElement("DAV::displayname", XMLWriter.NO_CONTENT);
            if (!isFolder) {
                xmlWriter.writeElement("DAV::getcontentlanguage",
                        XMLWriter.NO_CONTENT);
                xmlWriter.writeElement("DAV::getcontentlength",
                        XMLWriter.NO_CONTENT);
                xmlWriter.writeElement("DAV::getcontenttype",
                        XMLWriter.NO_CONTENT);
                xmlWriter.writeElement("DAV::getetag", XMLWriter.NO_CONTENT);
                xmlWriter.writeElement("DAV::getlastmodified",
                        XMLWriter.NO_CONTENT);
            }
            xmlWriter
                    .writeElement("DAV::resourcetype", XMLWriter.NO_CONTENT);
            xmlWriter.writeElement("DAV::supportedlock",
                    XMLWriter.NO_CONTENT);
            xmlWriter.writeElement("DAV::source", XMLWriter.NO_CONTENT);

            xmlWriter.writeElement("DAV::prop", XMLWriter.CLOSING);
            xmlWriter.writeElement("DAV::status", XMLWriter.OPENING);
            xmlWriter.writeText(status);
            xmlWriter.writeElement("DAV::status", XMLWriter.CLOSING);
            xmlWriter.writeElement("DAV::propstat", XMLWriter.CLOSING);

            break;

        case BY_PROPERTY:

            Vector<String> propertiesNotFound = new Vector<String>();

            // Parse the list of properties

            xmlWriter.writeElement("DAV::propstat", XMLWriter.OPENING);
            xmlWriter.writeElement("DAV::prop", XMLWriter.OPENING);

            Enumeration<String> properties = propertiesVector.elements();

            while (properties.hasMoreElements()) {

                String property = (String) properties.nextElement();

                if (property.equals("DAV::creationdate")) {
                    xmlWriter.writeProperty("DAV::creationdate",
                            creationdate);
                } else if (property.equals("DAV::displayname")) {
                    xmlWriter.writeElement("DAV::displayname",
                            XMLWriter.OPENING);
                    xmlWriter.writeData(resourceName);
                    xmlWriter.writeElement("DAV::displayname",
                            XMLWriter.CLOSING);
                } else if (property.equals("DAV::getcontentlanguage")) {
                    if (isFolder) {
                        propertiesNotFound.addElement(property);
                    } else {
                        xmlWriter.writeElement("DAV::getcontentlanguage",
                                XMLWriter.NO_CONTENT);
                    }
                } else if (property.equals("DAV::getcontentlength")) {
                    if (isFolder) {
                        propertiesNotFound.addElement(property);
                    } else {
                        xmlWriter.writeProperty("DAV::getcontentlength",
                                resourceLength);
                    }
                } else if (property.equals("DAV::getcontenttype")) {
                    if (isFolder) {
                        propertiesNotFound.addElement(property);
                    } else {
                        xmlWriter.writeProperty("DAV::getcontenttype",
                                mimeType);
                    }
                } else if (property.equals("DAV::getetag")) {
                    if (isFolder || so.isNullResource()) {
                        propertiesNotFound.addElement(property);
                    } else {
                        xmlWriter.writeProperty("DAV::getetag", getETag(so));
                    }
                } else if (property.equals("DAV::getlastmodified")) {
                    if (isFolder) {
                        propertiesNotFound.addElement(property);
                    } else {
                        xmlWriter.writeProperty("DAV::getlastmodified",
                                lastModified);
                    }
                } else if (property.equals("DAV::resourcetype")) {
                    if (isFolder) {
                        xmlWriter.writeElement("DAV::resourcetype",
                                XMLWriter.OPENING);
                        xmlWriter.writeElement("DAV::collection",
                                XMLWriter.NO_CONTENT);
                        xmlWriter.writeElement("DAV::resourcetype",
                                XMLWriter.CLOSING);
                    } else {
                        xmlWriter.writeElement("DAV::resourcetype",
                                XMLWriter.NO_CONTENT);
                    }
                } else if (property.equals("DAV::source")) {
                    xmlWriter.writeProperty("DAV::source", "");
                } else if (property.equals("DAV::supportedlock")) {

                    writeSupportedLockElements(transaction, xmlWriter, path);

                } else if (property.equals("DAV::lockdiscovery")) {

                    writeLockDiscoveryElements(transaction, xmlWriter, path);

                } else {
                    propertiesNotFound.addElement(property);
                }

            }

            xmlWriter.writeElement("DAV::prop", XMLWriter.CLOSING);
            xmlWriter.writeElement("DAV::status", XMLWriter.OPENING);
            xmlWriter.writeText(status);
            xmlWriter.writeElement("DAV::status", XMLWriter.CLOSING);
            xmlWriter.writeElement("DAV::propstat", XMLWriter.CLOSING);

            Enumeration<String> propertiesNotFoundList = propertiesNotFound
                    .elements();

            if (propertiesNotFoundList.hasMoreElements()) {

                status = new String("HTTP/1.1 " + WebDAVStatus.SC_NOT_FOUND
                        + " "
                        + WebDAVStatus.getStatusText(WebDAVStatus.SC_NOT_FOUND));

                xmlWriter.writeElement("DAV::propstat", XMLWriter.OPENING);
                xmlWriter.writeElement("DAV::prop", XMLWriter.OPENING);

                while (propertiesNotFoundList.hasMoreElements()) {
                    xmlWriter.writeElement((String) propertiesNotFoundList
                            .nextElement(), XMLWriter.NO_CONTENT);
                }

                xmlWriter.writeElement("DAV::prop", XMLWriter.CLOSING);
                xmlWriter.writeElement("DAV::status", XMLWriter.OPENING);
                xmlWriter.writeText(status);
                xmlWriter.writeElement("DAV::status", XMLWriter.CLOSING);
                xmlWriter.writeElement("DAV::propstat", XMLWriter.CLOSING);

            }

            break;

        }

        xmlWriter.writeElement("DAV::response", XMLWriter.CLOSING);

        so = null;
    }

    private void writeSupportedLockElements(ITransaction transaction,
            XMLWriter xmlWriter, String path) throws IOException {

        ILockedObject lo = _resourceLocks.getLockedObjectByPath(transaction,
                path);

        xmlWriter.writeElement("DAV::supportedlock", XMLWriter.OPENING);

        if (lo == null) {
            // both locks (shared/exclusive) can be granted
            xmlWriter.writeElement("DAV::lockentry", XMLWriter.OPENING);

            xmlWriter.writeElement("DAV::lockscope", XMLWriter.OPENING);
            xmlWriter.writeElement("DAV::exclusive", XMLWriter.NO_CONTENT);
            xmlWriter.writeElement("DAV::lockscope", XMLWriter.CLOSING);

            xmlWriter.writeElement("DAV::locktype", XMLWriter.OPENING);
            xmlWriter.writeElement("DAV::write", XMLWriter.NO_CONTENT);
            xmlWriter.writeElement("DAV::locktype", XMLWriter.CLOSING);

            xmlWriter.writeElement("DAV::lockentry", XMLWriter.CLOSING);

            xmlWriter.writeElement("DAV::lockentry", XMLWriter.OPENING);

            xmlWriter.writeElement("DAV::lockscope", XMLWriter.OPENING);
            xmlWriter.writeElement("DAV::shared", XMLWriter.NO_CONTENT);
            xmlWriter.writeElement("DAV::lockscope", XMLWriter.CLOSING);

            xmlWriter.writeElement("DAV::locktype", XMLWriter.OPENING);
            xmlWriter.writeElement("DAV::write", XMLWriter.NO_CONTENT);
            xmlWriter.writeElement("DAV::locktype", XMLWriter.CLOSING);

            xmlWriter.writeElement("DAV::lockentry", XMLWriter.CLOSING);

        } else {
            // LockObject exists, checking lock state
            // if an exclusive lock exists, no further lock is possible
            if (lo.isShared()) {

                xmlWriter.writeElement("DAV::lockentry", XMLWriter.OPENING);

                xmlWriter.writeElement("DAV::lockscope", XMLWriter.OPENING);
                xmlWriter.writeElement("DAV::shared", XMLWriter.NO_CONTENT);
                xmlWriter.writeElement("DAV::lockscope", XMLWriter.CLOSING);

                xmlWriter.writeElement("DAV::locktype", XMLWriter.OPENING);
                xmlWriter.writeElement("DAV::" + lo.getType(),
                        XMLWriter.NO_CONTENT);
                xmlWriter.writeElement("DAV::locktype", XMLWriter.CLOSING);

                xmlWriter.writeElement("DAV::lockentry", XMLWriter.CLOSING);
            }
        }

        xmlWriter.writeElement("DAV::supportedlock", XMLWriter.CLOSING);

        lo = null;
    }

    private void writeLockDiscoveryElements(ITransaction transaction, XMLWriter xmlWriter, String path) throws IOException {

        ILockedObject lo = _resourceLocks.getLockedObjectByPath(transaction, path);

        if (lo != null && !lo.hasExpired()) {

            xmlWriter.writeElement("DAV::lockdiscovery", XMLWriter.OPENING);
            xmlWriter.writeElement("DAV::activelock", XMLWriter.OPENING);

            xmlWriter.writeElement("DAV::locktype", XMLWriter.OPENING);
            xmlWriter.writeProperty("DAV::" + lo.getType());
            xmlWriter.writeElement("DAV::locktype", XMLWriter.CLOSING);

            xmlWriter.writeElement("DAV::lockscope", XMLWriter.OPENING);
            if (lo.isExclusive()) {
                xmlWriter.writeProperty("DAV::exclusive");
            } else {
                xmlWriter.writeProperty("DAV::shared");
            }
            xmlWriter.writeElement("DAV::lockscope", XMLWriter.CLOSING);

            xmlWriter.writeElement("DAV::depth", XMLWriter.OPENING);
            if (_depth == INFINITY) {
                xmlWriter.writeText("Infinity");
            } else {
                xmlWriter.writeText(String.valueOf(_depth));
            }
            xmlWriter.writeElement("DAV::depth", XMLWriter.CLOSING);

            String[] owners = lo.getOwner();
            if (owners != null) {
                for (int i = 0; i < owners.length; i++) {
                    xmlWriter.writeElement("DAV::owner", XMLWriter.OPENING);
                    xmlWriter.writeElement("DAV::href", XMLWriter.OPENING);
                    xmlWriter.writeText(owners[i]);
                    xmlWriter.writeElement("DAV::href", XMLWriter.CLOSING);
                    xmlWriter.writeElement("DAV::owner", XMLWriter.CLOSING);
                }
            } else {
                xmlWriter.writeElement("DAV::owner", XMLWriter.NO_CONTENT);
            }

            int timeout = (int) (lo.getTimeoutMillis() / 1000);
            String timeoutStr = new Integer(timeout).toString();
            xmlWriter.writeElement("DAV::timeout", XMLWriter.OPENING);
            xmlWriter.writeText("Second-" + timeoutStr);
            xmlWriter.writeElement("DAV::timeout", XMLWriter.CLOSING);

            String lockToken = lo.getID();

            xmlWriter.writeElement("DAV::locktoken", XMLWriter.OPENING);
            xmlWriter.writeElement("DAV::href", XMLWriter.OPENING);
            xmlWriter.writeText("opaquelocktoken:" + lockToken);
            xmlWriter.writeElement("DAV::href", XMLWriter.CLOSING);
            xmlWriter.writeElement("DAV::locktoken", XMLWriter.CLOSING);

            xmlWriter.writeElement("DAV::activelock", XMLWriter.CLOSING);
            xmlWriter.writeElement("DAV::lockdiscovery", XMLWriter.CLOSING);

        } else {
            xmlWriter.writeElement("DAV::lockdiscovery", XMLWriter.NO_CONTENT);
        }

        lo = null;
    }

}
