package de.mhus.cherry.renderer.jsp.tagext;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;

import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.TagSupport;

import de.mhus.cherry.portal.api.CallContext;
import de.mhus.cherry.portal.api.CherryApi;
import de.mhus.lib.cao.CaoNode;

public class NavigationTag extends TagSupport {

	private static final long serialVersionUID = 1L;
	private CaoNode res;
	private Collection<CaoNode> nodes;
	private boolean showHidden = false;
	private String order = null;
	private String resName;

	public void setResource(String resName) {
		this.resName = resName;
	}

	public void setRoot(CaoNode res) {
		this.res = res;
	}
	
	public void setShowHidden(boolean showHidden) {
		this.showHidden  = showHidden;
	}
	
	public void setOrder(String order) {
		this.order = order;
	}

	@Override
	public int doStartTag() throws JspException {
		
		if (res == null) {
			NavigationStack stack = NavigationStack.getStack(pageContext);
			if (stack.getCurrent() != null) res = stack.getCurrent().getCurrent();
			if (res == null) {
				CallContext call = (CallContext)pageContext.getAttribute("call");
//				res = call.getNavigationResource();
				res = call.getVirtualHost().getNavigationProvider().getNode("/"); // get root				
			}
		}
		
		nodes = res.getNodes();
		if (resName != null)
			pageContext.setAttribute(resName, res);

		if (!showHidden) {
			// remove hidden elements
			for (Iterator<CaoNode> iter = nodes.iterator(); iter.hasNext();) {
				CaoNode n = iter.next();
				if (n.getBoolean(CherryApi.NAV_HIDDEN, false))
					iter.remove();
			}
		}
		
		if (order != null) {
			LinkedList<CaoNode> list = new LinkedList<>( nodes );
			list.sort(new Comparator<CaoNode>() {

				@Override
				public int compare(CaoNode o1, CaoNode o2) {
					
					String s1 = o1.getString(order, null);
					String s2 = o2.getString(order, null);
					if (s1 == null && s2 == null) return 0;
					if (s1 == null) return -1;
					return s1.compareTo(s2);
				}
			});
			nodes = list;
		}
		
		NavigationStack stack = NavigationStack.getStack(pageContext);
		
		stack.push(new Navigation( res, nodes ));
		
    	return EVAL_BODY_INCLUDE;
	}
	
	@Override
	public int doAfterBody() throws JspException {
		NavigationStack stack = NavigationStack.getStack(pageContext);
		stack.pop();
		return SKIP_BODY;
	}
	
}
