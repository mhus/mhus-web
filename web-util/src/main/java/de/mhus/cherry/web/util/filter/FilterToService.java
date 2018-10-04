package de.mhus.cherry.web.util.filter;

import java.util.UUID;

import de.mhus.cherry.web.api.InternalCallContext;
import de.mhus.cherry.web.api.VirtualHost;
import de.mhus.cherry.web.api.WebFilter;
import de.mhus.lib.core.config.IConfig;
import de.mhus.lib.core.logging.MLogUtil;
import de.mhus.lib.errors.MException;
import de.mhus.lib.errors.NotFoundException;
import de.mhus.osgi.services.MOsgi;

public class FilterToService implements WebFilter {

	private IConfig config;
	private String serviceName;
	private WebFilter webFilter;
	private VirtualHost vHost;
	private UUID instanceId;

	@Override
	public void doInitialize(UUID instance, VirtualHost vHost, IConfig config) throws MException {
		this.config = config.getNode("config");
		serviceName = config.getString("service");
		this.vHost = vHost;
	}

	@Override
	public boolean doFilterBegin(UUID instance, InternalCallContext call) throws MException {
		check();
		if (webFilter == null) throw new NotFoundException("service not found",serviceName);
		return webFilter.doFilterBegin(instanceId, call);
	}

	@Override
	public void doFilterEnd(UUID instance, InternalCallContext call) throws MException {
		check();
		if (webFilter == null) throw new NotFoundException("service not found",serviceName);
		webFilter.doFilterEnd(instanceId, call);
	}

	private synchronized void check() {
		if (webFilter == null) {
			try {
				webFilter = MOsgi.getService(WebFilter.class,"(name=" + serviceName + ")");
				webFilter.doInitialize(instanceId, vHost, config);
			} catch (Throwable e) {
				MLogUtil.log().e(serviceName,e);
			}
		}
	}

}