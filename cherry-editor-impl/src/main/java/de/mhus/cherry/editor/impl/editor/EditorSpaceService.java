package de.mhus.cherry.editor.impl.editor;

import com.vaadin.ui.AbstractComponent;
import com.vaadin.ui.MenuBar;
import com.vaadin.ui.MenuBar.MenuItem;

import aQute.bnd.annotation.component.Component;
import de.mhus.lib.vaadin.desktop.GuiSpace;
import de.mhus.lib.vaadin.desktop.GuiSpaceService;

@Component(immediate=true,provide=GuiSpaceService.class)
public class EditorSpaceService extends GuiSpace {

	@Override
	public String getName() {
		return "editor";
	}

	@Override
	public String getDisplayName() {
		return "Editor";
	}

	@Override
	public AbstractComponent createSpace() {
		return new EditorSpace();
	}

	@Override
	public void createMenu(final AbstractComponent space, MenuItem[] menu) {
		menu[0].setVisible(true);
		menu[0].setText("File");
		menu[0].addItem("Save", new MenuBar.Command() {
			
			@Override
			public void menuSelected(MenuItem selectedItem) {
				((EditorSpace)space).doSave();
			}
		});
		menu[0].addSeparator();
		menu[0].addItem("Close", new MenuBar.Command() {
			
			@Override
			public void menuSelected(MenuItem selectedItem) {
				((EditorSpace)space).doCancel();
			}
		});
		
	}

	@Override
	public boolean isHiddenSpace() {
		return true;
	}

}
