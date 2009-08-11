package plugins.SiteToolPlugin.fproxy.dav.sampleimpl;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import plugins.SiteToolPlugin.SiteToolPlugin;
import plugins.SiteToolPlugin.fproxy.dav.api.IMimeTyper;
import plugins.SiteToolPlugin.fproxy.dav.api.IResourceLocks;
import plugins.SiteToolPlugin.fproxy.dav.api.ITransaction;
import plugins.SiteToolPlugin.fproxy.dav.api.IWebDAVStore;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoCopy;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoDelete;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoGet;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoHead;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoLock;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoMkcol;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoMove;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoNotImplemented;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoOptions;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoPropfind;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoProppatch;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoPut;
import plugins.SiteToolPlugin.fproxy.dav.methods.DoUnlock;
import plugins.SiteToolPlugin.toadlets.SitesToadlet;
import plugins.fproxy.lib.PluginContext;
import plugins.fproxy.lib.WebInterfaceToadlet;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.clients.http.annotation.AllowData;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

public class SampleDAVToadlet extends WebInterfaceToadlet {

	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(SampleDAVToadlet.class);
	}

	enum METHODS { GET, OPTIONS, PROPFIND, PROPPATCH, MKCOL, COPY,
		MOVE, LOCK, UNLOCK, HEAD, PUT, DELETE }

	private final IWebDAVStore store;

	private final SitesToadlet sitesToadlet;

	private DoGet doGet;
	private DoHead doHead;
	private DoDelete doDelete;
	private DoCopy doCopy;
	private DoLock doLock;
	private DoUnlock doUnlock;
	private DoMove doMove;
	private DoMkcol doMkcol;
	private DoOptions doOptions;
	private DoPut doPut;
	private DoPropfind doPropfind;
	private DoProppatch doProppatch;
	private DoNotImplemented doNotImplemented;

	private String dftIndexFile;

	private String insteadOf404;

	private IResourceLocks _resLocks;

	private IMimeTyper mimeTyper;

	private int nocontentLenghHeaders;

	private boolean READ_ONLY;

	private boolean lazyFolderCreationOnPut;

	private ITransaction _transaction;

	public SampleDAVToadlet(PluginContext stCtx, SitesToadlet sitesToadlet2) {
		super(stCtx, SiteToolPlugin.PLUGIN_URI, "DAV");
		this.sitesToadlet = sitesToadlet2;
		store = new LocalFileSystemStore(new File("davdemo"));
		_resLocks = new SimpleResourceLocks();
		init();
	}

	private void init() {
		// TODO / FIXME
		mimeTyper = new IMimeTyper() {
            public String getMimeType(String path) {
                return "mimetype";
            }
        };
        doGet = new DoGet(store, dftIndexFile, insteadOf404, _resLocks, mimeTyper, nocontentLenghHeaders);
        doHead = new DoHead(store, dftIndexFile, insteadOf404, _resLocks, mimeTyper, nocontentLenghHeaders);
        doDelete = new DoDelete(store, _resLocks, READ_ONLY);
        doCopy = new DoCopy(store, _resLocks, doDelete, READ_ONLY);
        doLock = new DoLock(store, _resLocks, READ_ONLY);
        doUnlock = new DoUnlock(store, _resLocks, READ_ONLY);
        doMove = new DoMove(_resLocks, doDelete, doCopy, READ_ONLY);
        doMkcol = new DoMkcol(store, _resLocks, READ_ONLY);
        doOptions = new DoOptions(store, _resLocks);
        doPut = new DoPut(store, _resLocks, READ_ONLY, lazyFolderCreationOnPut);
        doPropfind = new DoPropfind(store, _resLocks, mimeTyper);
        doProppatch = new DoProppatch(store, _resLocks, READ_ONLY);
        doNotImplemented = new DoNotImplemented(READ_ONLY);
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handle(METHODS.GET, _transaction, uri, req, ctx);
	}

	public void handleMethodOPTIONS(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, 	RedirectException {
		handle(METHODS.OPTIONS, _transaction, uri, req, ctx);
	}

	@AllowData(true)
	public void handleMethodPROPFIND(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handle(METHODS.PROPFIND, _transaction, uri, req, ctx);
	}

	@AllowData(true)
	public void handleMethodPROPPATCH(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handle(METHODS.PROPPATCH, _transaction, uri, req, ctx);
	}

	public void handleMethodMKCOL(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handle(METHODS.MKCOL, _transaction, uri, req, ctx);
	}

	public void handleMethodCOPY(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, 	RedirectException {
		handle(METHODS.COPY, _transaction, uri, req, ctx);
	}

	public void handleMethodMOVE(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, 	RedirectException {
		handle(METHODS.MOVE, _transaction, uri, req, ctx);
	}

	public void handleMethodLOCK(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, 	RedirectException {
		handle(METHODS.LOCK, _transaction, uri, req, ctx);
	}

	public void handleMethodUNLOCK(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, 	RedirectException {
		handle(METHODS.UNLOCK, _transaction, uri, req, ctx);
	}

	public void handleMethodHEAD(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handle(METHODS.HEAD, _transaction, uri, req, ctx);
	}

	public void handleMethodPUT(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handle(METHODS.PUT, _transaction, uri, req, ctx);
	}

	public void handleMethodDELETE(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		handle(METHODS.DELETE, _transaction, uri, req, ctx);
	}

	private void handle(METHODS method, ITransaction transaction, URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {

		switch (method) {
			case GET:		doGet.handle(_transaction, uri, req, ctx); break;
			case OPTIONS:	doOptions.handle(_transaction, uri, req, ctx); break;
			case PROPFIND:	doPropfind.handle(_transaction, uri, req, ctx); break;
			case PROPPATCH:	doProppatch.handle(_transaction, uri, req, ctx); break;
			case MKCOL:		doMkcol.handle(_transaction, uri, req, ctx); break;
			case COPY:		doCopy.handle(_transaction, uri, req, ctx); break;
			case MOVE:		doMove.handle(_transaction, uri, req, ctx); break;
			case LOCK:		doLock.handle(_transaction, uri, req, ctx); break;
			case UNLOCK:	doUnlock.handle(_transaction, uri, req, ctx); break;
			case HEAD:		doHead.handle(_transaction, uri, req, ctx); break;
			case PUT:		doPut.handle(_transaction, uri, req, ctx); break;
			case DELETE:	doDelete.handle(_transaction, uri, req, ctx); break;
			default:		doNotImplemented.handle(_transaction, uri, req, ctx);
		}

	}

	@Override
	public Toadlet showAsToadlet() {
		return sitesToadlet;
	}

}
