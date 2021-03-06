package aQute.impl.xray;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.apache.felix.scr.*;
import org.apache.felix.webconsole.*;
import org.osgi.framework.*;
import org.osgi.framework.hooks.service.*;
import org.osgi.framework.hooks.service.ListenerHook.ListenerInfo;
import org.osgi.service.log.*;

import aQute.bnd.annotation.component.*;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Reference;
import aQute.impl.xray.Data.*;
import aQute.impl.xray.Data.BundleDef.*;
import aQute.lib.collections.*;
import aQute.lib.io.*;
import aQute.lib.json.*;

/**
 * This is a servlet that provides the stat of the OSGi framework in a JSON
 * file. The state is represented as a {@link Result} data object. The servlet
 * is designed to work inside the Felix Web Console plugin model.
 * 
 * This is a final class because we assume resources are in the package so
 * subclassing might kill this.
 */

@Component(provide = {Servlet.class}, properties = {"felix.webconsole.label=xray"})
@SuppressWarnings("rawtypes")
public final class XRayWebPlugin extends AbstractWebConsolePlugin {
	private static final long			serialVersionUID		= 1L;
	private static String				PLUGIN_NAME				= "xray";

	final static int					TITLE_LENGTH			= 14;
	final static Pattern				LISTENER_INFO_PATTERN	= Pattern
																		.compile("\\(objectClass=([^)]+)\\)");
	final static JSONCodec				codec					= new JSONCodec();

	private BundleContext				context;
	private LogReaderService			logReader;
	private LogService					log;
	private ScrService					scr;
	private MultiMap<String, Bundle>	listeners				= new MultiMap<String, Bundle>();
	private ServiceRegistration			lhook;

	/*
	 * Called at startup
	 */
	@Activate
	public void activate(BundleContext context) {
		super.activate(context);
		this.context = context;

		/*
		 * Register a ListenerHook to find out about any services that are
		 * searched for.
		 */
		lhook = context.registerService(ListenerHook.class.getName(),
				new ListenerHook() {

					public synchronized void added(Collection listeners) {
						for (Object o : listeners) {
							addListenerInfo((ListenerInfo) o);
						}
					}

					public synchronized void removed(Collection listeners) {
						for (Object o : listeners) {
							removeListenerInfo((ListenerInfo) o);
						}
					}
				}, null);
	}

	/*
	 * Called at going down
	 */
	@Deactivate
	public void deactivate(BundleContext context) {
		lhook.unregister();
		super.deactivate();
	}

	/**
	 * Required by the WebConsolePlugin
	 */
	@Override
	public String getLabel() {
		return "xray";
	}

	/**
	 * Required by the WebConsolePlugin
	 */
	@Override
	public String getTitle() {
		return "X-Ray";
	}

	/**
	 * We need to be able to serve the state easily. So we implement the doGet
	 * and let the superclass handle anything but the state data.
	 */
	public void doGet(HttpServletRequest rq, HttpServletResponse rsp)
			throws ServletException, IOException {
		if (rq.getPathInfo().endsWith("/state.json"))
			getState(rq, rsp);
		else
			super.doGet(rq, rsp);
	}

	/**
	 * Create the state file.
	 */
	private void getState(HttpServletRequest rq, HttpServletResponse rsp) {
		try {
			// Can specify service names to ignore
			String[] services = rq.getParameterValues("ignore");

			Result result = build(services);
			result.root = rq.getServletPath();

			codec.enc().to(rsp.getWriter()).writeDefaults().put(result);

		} catch (Exception e) {
			log.log(LogService.LOG_ERROR, "Failed to create state file", e);
		}
	}

	/**
	 * Required by the Web Console Plugin to render an content. In out idea this
	 * is just the content of the content area of the webconsole. The Web
	 * Console is adding headers etc.
	 */
	@Override
	protected void renderContent(HttpServletRequest rq, HttpServletResponse rsp)
			throws ServletException, IOException {
		IO.copy(getClass().getResourceAsStream("index.html"), rsp.getWriter());
	}

	/**
	 * Includes for the head element.
	 */
	@Override
	public String[] getCssReferences() {
		return new String[] {"/"+PLUGIN_NAME+"/style.css"};
	}

	/**
	 * Standard referring to statics. All resources should be in this package.
	 */
	public URL getResource(String resource) {
		if (resource.equals("/" + PLUGIN_NAME))
			return null;

		resource = resource.replaceAll("/"+PLUGIN_NAME+"/", "");
		URL url = getClass().getResource(resource);
		return url;
	}

	/**
	 * Build up the graph
	 * 
	 * @param ignoredServices
	 * 
	 * @throws InvalidSyntaxException
	 */
	Result build(String[] ignoredServices) throws InvalidSyntaxException {
		Map<String, ServiceDef> icons = new TreeMap<String, ServiceDef>();
		Map<Bundle, BundleDef> bundles = new LinkedHashMap<Bundle, BundleDef>();

		Bundle[] bs = context.getBundles();
		int row = 0;
		for (Bundle bundle : bs) {
			BundleDef data = data(bundle);
			data.row = row++;
			bundles.put(bundle, data);
		}

		for (String name : listeners.keySet()) {
			ServiceDef icon = icons.get(name);
			if (icon == null) {
				icon = new ServiceDef();
				icons.put(name, icon);
				icon.name = name;
				icon.shortName = from(TITLE_LENGTH, name);
			}
			for (Iterator<Bundle> i = listeners.get(name).iterator(); i
					.hasNext();) {
				Bundle b = i.next();
				BundleDef bdef = bundles.get(b);
				if (bdef == null)
					i.remove();
				else
					icon.listening.add(bdef.row);
			}
		}

		for (ServiceReference reference : context.getServiceReferences(null,
				null)) {

			for (String name : (String[]) reference.getProperty("objectClass")) {
				ServiceDef icon = icons.get(name);
				if (icon == null) {
					icon = new ServiceDef();
					icons.put(name, icon);
					icon.name = name;
					icon.shortName = from(TITLE_LENGTH, name);
				}
				if (reference.getUsingBundles() != null)
					for (Bundle b : reference.getUsingBundles()) {
						int r = bundles.get(b).row;
						icon.getting.add(r);
					}

				int r = bundles.get(reference.getBundle()).row;
				icon.registering.add(r);
			}
		}

		if (ignoredServices != null)
			for (String s : ignoredServices)
				icons.remove(s);

		int column = 0;
		ServiceDef previous = null;
		for (String key : icons.keySet()) {
			ServiceDef icon = icons.get(key);
			if (icon.registering.size() > 0)
				icon.row = icon.registering.first();
			else if (icon.listening.size() > 0)
				icon.row = icon.listening.first();
			else
				icon.row = 0;

			if (previous != null && previous.row == icon.row)
				icon.row++;
			icon.column = column++;
			previous = icon;
		}

		Result result = new Result();
		result.bundles = new ArrayList<BundleDef>(bundles.values());
		result.services = new ArrayList<ServiceDef>(icons.values());

		return result;
	}

	/**
	 * Try to construct a readable name from a fqn that is likely too long
	 */
	String from(int n, String... strings) {
		for (String s : strings) {
			if (s != null) {
				if (s.length() > n) {
					s = s.substring(s.lastIndexOf('.') + 1);
					if (s.length() > n) {
						s = s.substring(s.lastIndexOf('.') + 1);
						if (s.length() > n) {
							if (s.endsWith("Listener"))
								s = s.substring(0,
										s.length() - "istener".length())
										+ ".";
							else if (s.endsWith("Service"))
								s = s.substring(0,
										s.length() - "ervice".length())
										+ ".";
							if (s.length() > n) {
								StringBuilder sb = new StringBuilder();
								for (int i = 0; i < s.length()
										&& sb.length() < n; i++) {
									if ("aeiouy".indexOf(s.charAt(i)) < 0)
										sb.append(s.charAt(i));
								}
								s = sb.toString();
							}
							if (s.length() > n) {
								s = s.substring(0, 12) + "..";
							}
						}
					}
				}
				return s;
			}
		}
		return "<>";
	}

	/**
	 * Create the Bundle Definition
	 */
	private BundleDef data(Bundle bundle) {
		BundleDef bd = new BundleDef();
		bd.id = bundle.getBundleId();
		bd.bsn = bundle.getSymbolicName();
		bd.name = from(15, bd.bsn,
				(String) bundle.getHeaders().get("Bundle-Name"), bd.id + "");

		switch (bundle.getState()) {
			case Bundle.INSTALLED :
				bd.state = STATE.INSTALLED;
				break;
			case Bundle.RESOLVED :
				bd.state = STATE.RESOLVED;
				break;
			case Bundle.STARTING :
				bd.state = STATE.STARTING;
				break;
			case Bundle.STOPPING :
				bd.state = STATE.STOPPING;
				break;
			case Bundle.UNINSTALLED :
				bd.state = STATE.UNINSTALLED;
				break;
			case Bundle.ACTIVE :
				bd.state = STATE.ACTIVE;
				break;

			default :
				bd.state = STATE.UNKNOWN;
				break;
		}

		if (bundle.getState() == Bundle.STARTING
				|| bundle.getState() == Bundle.ACTIVE) {
			if (scr != null)
				doComponents(bundle, bd);
		}

		if (logReader != null)
			doLog(bundle, bd);

		return bd;
	}

	/**
	 * Use the ScrService to create the components
	 */
	private void doComponents(Bundle bundle, BundleDef bd) {
		org.apache.felix.scr.Component[] components = scr.getComponents(bundle);
		if (components != null) {
			for (org.apache.felix.scr.Component component : components) {
				ComponentDef cdef = new ComponentDef();
				cdef.unsatisfied = component.getState() == org.apache.felix.scr.Component.STATE_UNSATISFIED;
				cdef.name = component.getName();
				cdef.services = component.getServices();
				cdef.index = bd.components.size();
				cdef.id = component.getId();
				if (component.getReferences() != null) {
					for (org.apache.felix.scr.Reference ref : component
							.getReferences()) {
						cdef.references.add(ref.getServiceName());
					}
				}
				bd.components.add(cdef);
			}
		}
	}

	/**
	 * Use the LogReaderService to find out about log messages
	 */
	private void doLog(Bundle bundle, BundleDef bd) {
		@SuppressWarnings("unchecked")
		Enumeration<LogEntry> e = logReader.getLog();
		StringBuilder sb = new StringBuilder();
		Formatter f = new Formatter(sb);

		while (e.hasMoreElements()) {
			LogEntry entry = e.nextElement();
			if (entry.getBundle() == bundle) {
				if (entry.getTime() + 2 * 60 * 1000 > System
						.currentTimeMillis()) {
					if (entry.getLevel() <= LogService.LOG_WARNING) {
						f.format(
								"%s:%s %s\n",
								entry.getLevel() == LogService.LOG_WARNING ? "W"
										: "E", entry.getMessage(), (entry
										.getException() == null ? "" : entry
										.getException().getMessage()));
						if (entry.getLevel() == LogService.LOG_WARNING)
							bd.errors |= true;
					}
				}
			}
		}
		bd.log = sb.toString();
	}

	@Reference(type = '?')
	void setLogReader(LogReaderService log) {
		this.logReader = log;
	}

	@Reference(type = '?')
	void setLog(LogService log) {
		this.log = log;
	}

	@Reference(type = '?')
	void setScr(ScrService scr) {
		this.scr = scr;
	}

	private synchronized void addListenerInfo(ListenerInfo o) {
		String filter = o.getFilter();
		if (filter != null) {
			Matcher m = LISTENER_INFO_PATTERN.matcher(filter);
			while (m.find()) {
				listeners.add(m.group(1), o.getBundleContext().getBundle());
			}
		}
	}

	private synchronized void removeListenerInfo(ListenerInfo o) {
		String filter = o.getFilter();
		if (filter != null) {
			Matcher m = LISTENER_INFO_PATTERN.matcher(filter);
			while (m.find()) {
				listeners.remove(m.group(1), o.getBundleContext().getBundle());
			}
		}
	}
}