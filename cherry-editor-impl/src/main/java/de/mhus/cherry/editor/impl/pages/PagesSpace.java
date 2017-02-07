package de.mhus.cherry.editor.impl.pages;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;

import com.vaadin.data.Item;
import com.vaadin.data.Property.ReadOnlyException;
import com.vaadin.data.Property.ValueChangeEvent;
import com.vaadin.data.Property.ValueChangeListener;
import com.vaadin.data.util.HierarchicalContainer;
import com.vaadin.event.Action;
import com.vaadin.server.FontAwesome;
import com.vaadin.ui.AbstractSelect.ItemCaptionMode;
import com.vaadin.ui.Accordion;
import com.vaadin.ui.Component;
import com.vaadin.ui.HorizontalSplitPanel;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TabSheet.SelectedTabChangeEvent;
import com.vaadin.ui.TabSheet.SelectedTabChangeListener;
import com.vaadin.ui.Tree;
import com.vaadin.ui.Tree.CollapseEvent;
import com.vaadin.ui.Tree.ExpandEvent;
import com.vaadin.ui.TreeTable;
import com.vaadin.ui.VerticalLayout;

import de.mhus.cherry.editor.impl.ControlUi;
import de.mhus.cherry.portal.api.CherryApi;
import de.mhus.cherry.portal.api.NavNode;
import de.mhus.cherry.portal.api.NavNode.TYPE;
import de.mhus.cherry.portal.api.VirtualHost;
import de.mhus.cherry.portal.api.WidgetApi;
import de.mhus.cherry.portal.api.control.ControlParent;
import de.mhus.cherry.portal.api.control.GuiUtil;
import de.mhus.cherry.portal.api.control.PageControl;
import de.mhus.cherry.portal.api.control.PageControlFactory;
import de.mhus.cherry.portal.api.util.CherryUtil;
import de.mhus.lib.cao.CaoNode;
import de.mhus.lib.core.MString;
import de.mhus.lib.core.logging.MLogUtil;
import de.mhus.lib.core.security.Account;
import de.mhus.lib.errors.MException;
import de.mhus.lib.vaadin.desktop.GuiLifecycle;
import de.mhus.lib.vaadin.desktop.Navigable;
import de.mhus.osgi.sop.api.Sop;
import de.mhus.osgi.sop.api.aaa.AccessApi;

public class PagesSpace extends VerticalLayout implements Navigable, GuiLifecycle, ControlParent {

	private static final long serialVersionUID = 1L;
	private Panel panel;
	private VerticalLayout contentLayout;
	private HorizontalSplitPanel split;
	private TreeTable tree;
	private Accordion controlAcc;
	private HashMap<PageControl,String> controls;
	private Action actionReload;

	@Override
	public void doInitialize() {
		
		panel = new Panel();
		setMargin(true);
		addComponent(panel);
		panel.setCaption("Pages");
		panel.setSizeFull();
		split = new HorizontalSplitPanel();
		split.setSizeFull();
		panel.setContent(split);
		
		tree = new TreeTable("Navigation");
		
		
		
		split.setFirstComponent(tree);
		split.setSecondComponent(contentLayout);
		split.setSplitPosition(75, Unit.PERCENTAGE);

		tree.setImmediate(true);
		tree.setItemCaptionMode(ItemCaptionMode.PROPERTY);
		tree.setItemCaptionPropertyId("name");
		tree.setContainerDataSource(createNavigationContainer());
		tree.addExpandListener(new Tree.ExpandListener() {
			private static final long serialVersionUID = 1L;

			@Override
			public void nodeExpand(ExpandEvent event) {
				doExpand(event.getItemId());
			}

		});		
		tree.addCollapseListener(new Tree.CollapseListener() {
			private static final long serialVersionUID = 1L;
			
			@Override
			public void nodeCollapse(CollapseEvent event) {
				doCollapse(event.getItemId());
			}
		});
		tree.addValueChangeListener(new ValueChangeListener() {
			private static final long serialVersionUID = 1L;
			
			@Override
			public void valueChange(ValueChangeEvent event) {
				doUpdateControl();
			}
		});
		
		tree.setSizeFull();
		tree.setSelectable(true);
		tree.setItemIconPropertyId("icon");
		tree.setVisibleColumns("name","tecName","hidden","acl","theme","pageType");
		tree.setColumnHeaders("Navigation","Name","Hidden","ACL","Theme","Type");
		
		actionReload = new Action("Reload");
		tree.addActionHandler(new Action.Handler() {
			
			@Override
			public void handleAction(Action action, Object sender, Object target) {
				if (action == actionReload) {
					String id = (String) tree.getValue();
            		if (id != null ) {
            			doRefreshNode(id);
            		}
				}
			}
			
			@Override
			public Action[] getActions(Object target, Object sender) {
				return new Action[] { actionReload };
			}
		});
		
		controlAcc = new Accordion();
		controlAcc.setSizeFull();
		split.setSecondComponent(controlAcc);
		controlAcc.addSelectedTabChangeListener(new SelectedTabChangeListener() {
			private static final long serialVersionUID = 1L;

			@Override
			public void selectedTabChange(SelectedTabChangeEvent event) {
				doUpdateControl();
			}
		});
		
		controls = new HashMap<>();
		AccessApi aaa = Sop.getApi(AccessApi.class);
		Account account = aaa.getCurrentOrGuest().getAccount();
		PageControl controlTab = null;
		for (PageControlFactory factory : CherryUtil.orderServices( PagesSpace.class, PageControlFactory.class ) ) {
			if (aaa.hasGroupAccess(account, PagesSpace.class, factory.getName(), "create")) {
				String name = factory.getName();
				PageControl control = factory.createPageControl();
				controls.put(control, name);
				controlAcc.addTab(control, name);
				control.doInit(this);
				if ("Control".equals(name))
					controlTab = control;
			}
		}
		if (controlTab != null)
			controlAcc.setSelectedTab(controlTab);
	}

	protected void doUpdateControl() {
		Component selected = controlAcc.getSelectedTab();
		
		String name = controls.get(selected);
		if (name == null) return;
		
		((PageControl)selected).doClean();
		
		String selId = (String)tree.getValue();
		if (selId == null) return;
		Item sel = (Item)tree.getItem(selId);
		if (sel == null) return;

		NavNode nav = (NavNode)sel.getItemProperty("object").getValue();

		((PageControl)selected).doUpdate(nav);

	}

	protected void doCollapse(Object itemId) {
//		HierarchicalContainer container = (HierarchicalContainer)tree.getContainerDataSource();
//		Item item = container.getItem(itemId);
//		for (Object c : container.getChildren(itemId))
//			container.removeItemRecursively(c);
	}

	protected void doExpand(Object itemId) {
		try {
			HierarchicalContainer container = (HierarchicalContainer)tree.getContainerDataSource();
			Item item = container.getItem(itemId);
			NavNode node = (NavNode) item.getItemProperty("object").getValue();
			Collection<?> children = tree.getChildren(itemId);
			if (children != null && children.size() != 0) return;
					
			Collection<NavNode> nodeChildren = node.getAllNodes();
			if (nodeChildren.size() == 0) return;
			
			// sort nav nodes
			for (NavNode n : nodeChildren) {
				try {
					Item next = container.addItem(n.getId());
					container.setParent(n.getId(), itemId);
					fillItem(next, n);
					container.setChildrenAllowed(n.getId(), true);
					tree.setCollapsed(n.getId(), true);
				} catch (Throwable t) {
					MLogUtil.log().i(t);
				}
			}
			
			tree.markAsDirty();
		} catch (Throwable t) {
			MLogUtil.log().i(t);
		}
	}

	private HierarchicalContainer createNavigationContainer() {
		HierarchicalContainer container = new HierarchicalContainer();
		container.addContainerProperty("name", String.class, "?");
		container.addContainerProperty("tecName", String.class, "");
		container.addContainerProperty("object", NavNode.class, null);
		container.addContainerProperty("theme", String.class, false);
		container.addContainerProperty("acl", Boolean.class, false);
		container.addContainerProperty("pageType", String.class, "");
		container.addContainerProperty("hidden", Boolean.class, false);
		container.addContainerProperty("icon", FontAwesome.class, null);
		
		String host = ((ControlUi)GuiUtil.getApi()).getHost();
		VirtualHost vHost = Sop.getApi(CherryApi.class).findVirtualHost(host);
		NavNode navRoot = vHost.getNavigationProvider().getNode("/");
		
		try {
			Item item = container.addItem(navRoot.getId());
			fillItem(item, navRoot);
			container.setParent(navRoot.getId(), null);
			container.setChildrenAllowed(navRoot.getId(), true);
		} catch (Throwable t) {
			MLogUtil.log().i(t);
		}
		return container;
	}

	@SuppressWarnings("unchecked")
	private void fillItem(Item item, NavNode node) throws ReadOnlyException, MException {
		
		String renderer = node.getRes() == null ? null : node.getRes().getString(WidgetApi.RENDERER, null);

		CaoNode itemRes = null;
		item.getItemProperty("object").setValue(node);
		if (node.getType() == TYPE.NAVIGATION) {
			itemRes = node.getNav();
			item.getItemProperty("icon").setValue( FontAwesome.FOLDER );
		} else 
		if (node.getType() == TYPE.PAGE) {
			itemRes = node.getRes();
			item.getItemProperty("icon").setValue( FontAwesome.FOLDER_O );
		} else 
		if (node.getType() == TYPE.RESOURCE) {
			itemRes = node.getRes();
			if (MString.isSet(renderer))
				item.getItemProperty("icon").setValue( FontAwesome.FILE );
			else
				item.getItemProperty("icon").setValue( FontAwesome.FILE_O );
		}
		
		boolean hasAcl = false;
		for (String key : itemRes.getPropertyKeys())
			if (key.startsWith("acl:")) {
				hasAcl = true;
				break;
			}
		item.getItemProperty("name").setValue("  " + itemRes.getString("title", itemRes.getName()) );
		item.getItemProperty("tecName").setValue(itemRes.getName());
		item.getItemProperty("hidden").setValue(itemRes.getBoolean(CherryApi.NAV_HIDDEN, false));
		item.getItemProperty("acl").setValue( hasAcl );
		String theme = node.getNav().getString(WidgetApi.THEME, null); 
		if (theme != null && MString.isIndex(theme, '.')) theme = MString.afterLastIndex(theme, '.');
		item.getItemProperty("theme").setValue( theme );
		
		String pageType = "";
		if (renderer != null) {
			if (MString.isIndex(renderer, '.')) renderer = MString.afterLastIndex(renderer, '.');
			pageType = renderer;
		}
		item.getItemProperty("pageType").setValue( pageType );
			
	}

	@Override
	public void doDestroy() {
	}

	@Override
	public String navigateTo(String selection, String filter) {
		return null;
	}

	@Override
	public void doRefreshNode(CaoNode node) {
		doRefreshNode(node.getId());
	}
	
	public void doRefreshNode(String id) {
		Item item = tree.getItem(id);
		if (item == null) return;
		boolean collapsed = tree.isCollapsed(id);
		HierarchicalContainer container = (HierarchicalContainer)tree.getContainerDataSource();
		for (Object child : new LinkedList<>( container.getChildren(id)) ) {
			container.removeItemRecursively(child);
		}
		if (!collapsed) {
			doExpand(id);
		}
		tree.markAsDirty();
	}

	@Override
	public void onShowSpace(boolean firstTime) {
		// TODO Auto-generated method stub
		
	}

}
