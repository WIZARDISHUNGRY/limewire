package org.limewire.ui.swing.components;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.LayoutManager;

import javax.swing.JLabel;
import javax.swing.JPanel;

import net.miginfocom.swing.MigLayout;

import org.jdesktop.swingx.JXLabel;
import org.jdesktop.swingx.JXPanel;
import org.limewire.ui.swing.painter.TextShadowPainter;
import org.limewire.ui.swing.util.GuiUtils;
import org.limewire.ui.swing.util.ResizeUtils;

public class LimeHeaderBar extends JXPanel {
    
    private final Component titleComponent;
    private final JPanel componentContainer;
    
    private JLabel titleTextComponent = null;
    
    /**
     * The default height of components added to the
     *  content pane.  Accessed with setDefaultComponentHeight(int)  
     *  
     * NOTE: When -1 no default will be set
     */
    private int defaultCompHeight = -1;

    public LimeHeaderBar() {
        this("");
    }
    
    public LimeHeaderBar(String title) {
        GuiUtils.assignResources(this);
        
        JXLabel headerLabel = new JXLabel(title);
        headerLabel.setForegroundPainter(new TextShadowPainter());
        
        this.titleComponent = headerLabel;
        this.componentContainer = new JPanel();
        
        init();
    }
    
    public LimeHeaderBar(Component titleComponent) {
        GuiUtils.assignResources(this);
        
        this.titleComponent = titleComponent;
        this.componentContainer = new JPanel();
        
        init();
    }

    private void init() {

        if (titleComponent instanceof JLabel) {
            titleTextComponent = (JLabel) titleComponent;
        }
        
        this.componentContainer.setOpaque(false);
        
        super.setLayout(new MigLayout("insets 0, fill, aligny center","[][]",""));
        super.add(titleComponent, "growy, dock west, gapbefore 5, gapafter 10");
        super.add(componentContainer, "grow, right");
    }
    
    @Override
    public Component add(Component comp) {
        forceHeight(comp);
        
        return this.componentContainer.add(comp);
    }
    
    @Override
    public Component add(Component comp, int index) {
        forceHeight(comp);
        
        return this.componentContainer.add(comp, index);
    }
    
    @Override
    public void add(Component comp, Object constraints) {
        forceHeight(comp);
        
        this.componentContainer.add(comp, constraints);
    }
    
    @Override
    public void add(Component comp, Object constraints, int index) {
        forceHeight(comp);
        
        this.componentContainer.add(comp, constraints, index);
    }
    
    @Override
    public void setLayout(LayoutManager mgr) {
        if (this.componentContainer == null) {
            super.setLayout(mgr);
        } 
        else {
            this.componentContainer.setLayout(mgr);
        }
    }
    
    public void setDefaultComponentHeight(int height) {
        defaultCompHeight = height;
    }
    
    /**
     * If the titleComponent is compound and not 
     *  a JLabel this method can be used to link
     *  the headers set text to a specific label
     */
    public void linkTextComponent(JLabel label) {
        titleTextComponent = label;
    }
    
    /**
     * Sets the headers text, usually the title 
     */
    public void setText(String text) {
        if (titleTextComponent != null) {
            titleTextComponent.setText(text);
        }
    }
    
    @Override
    public void setFont(Font font) {
        if (this.titleTextComponent == null) {
            super.setFont(font);
        }
        else {            
            this.titleTextComponent.setFont(font);
        }
    }
    
    @Override
    public void setForeground(Color fg) {
        if (this.titleTextComponent == null) {
            super.setForeground(fg);
        }
        else {            
            this.titleTextComponent.setForeground(fg);
        }
    }
    
    private void forceHeight(Component comp) {
        if (defaultCompHeight != -1) {
            ResizeUtils.forceHeight(comp, defaultCompHeight);
        }
    }
}
