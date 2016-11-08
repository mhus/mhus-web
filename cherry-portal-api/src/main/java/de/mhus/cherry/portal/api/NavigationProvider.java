package de.mhus.cherry.portal.api;

import de.mhus.lib.cao.CaoNode;

public interface NavigationProvider {

	String RESOURCE_ID = "cherry:resource_id";

	public CaoNode getNode(String path);
	
}
