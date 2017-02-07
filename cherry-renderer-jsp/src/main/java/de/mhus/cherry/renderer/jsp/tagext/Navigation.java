package de.mhus.cherry.renderer.jsp.tagext;

import java.util.Collection;

import de.mhus.lib.cao.CaoNode;

public class Navigation {

	private CaoNode parent;
	private Collection<CaoNode> nodes;
	private CaoNode current;

	public Navigation(CaoNode parent, Collection<CaoNode> nodes) {
		this.parent = parent;
		this.nodes = nodes;
	}

	public Collection<CaoNode> getNodes() {
		return nodes;
	}

	public void setCurrent(CaoNode current) {
		this.current = current;
	}

	public CaoNode getCurrent() {
		return current;
	}

	public CaoNode getParent() {
		return parent;
	}

}
