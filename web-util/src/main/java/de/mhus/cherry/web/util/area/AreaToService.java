package de.mhus.cherry.web.util.area;

import de.mhus.cherry.web.api.CallContext;
import de.mhus.cherry.web.api.VirtualHost;
import de.mhus.cherry.web.api.WebArea;
import de.mhus.lib.core.config.IConfig;
import de.mhus.lib.core.logging.MLogUtil;
import de.mhus.lib.errors.MException;
import de.mhus.lib.errors.NotFoundException;
import de.mhus.osgi.services.MOsgi;

public class AreaToService implements WebArea {

	private IConfig config;
	private String serviceName;
	private WebArea webArea;
	private VirtualHost vHost;

	@Override
	public void doInitialize(VirtualHost vHost, IConfig config) throws MException {
		this.config = config;
		serviceName = config.getString("service");
		vHost = this.vHost;
	}

	@Override
	public boolean doRequest(CallContext call) throws MException {
		check();
		if (webArea == null) throw new NotFoundException("service not found",serviceName);
		return webArea.doRequest(call);
	}

	private synchronized void check() {
		if (webArea == null) {
			try {
				webArea = MOsgi.getService(WebArea.class,"(name=" + serviceName + ")");
				webArea .doInitialize(vHost, config);
			} catch (Throwable e) {
				MLogUtil.log().e(serviceName,e);
			}
		}
	}

}
