/*
 * ------------------------------------------------------------------------
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * -------------------------------------------------------------------
 *
 * History
 *   26.05.2005 (Florian Georg): created
 */
package org.knime.workbench.editor2.editparts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EventObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.draw2d.ConnectionLayer;
import org.eclipse.draw2d.LayoutManager;
import org.eclipse.gef.CompoundSnapToHelper;
import org.eclipse.gef.EditPart;
import org.eclipse.gef.EditPartViewer;
import org.eclipse.gef.EditPolicy;
import org.eclipse.gef.LayerConstants;
import org.eclipse.gef.SnapToGrid;
import org.eclipse.gef.SnapToGuides;
import org.eclipse.gef.SnapToHelper;
import org.eclipse.gef.commands.CommandStackListener;
import org.eclipse.gef.editparts.ZoomManager;
import org.eclipse.gef.rulers.RulerProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.workflow.Annotation;
import org.knime.core.node.workflow.NodeAnnotation;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.node.workflow.NodeUIInformation;
import org.knime.core.node.workflow.NodeUIInformationEvent;
import org.knime.core.node.workflow.NodeUIInformationListener;
import org.knime.core.node.workflow.WorkflowAnnotation;
import org.knime.core.node.workflow.WorkflowEvent;
import org.knime.core.node.workflow.WorkflowListener;
import org.knime.core.node.workflow.WorkflowManager;
import org.knime.core.ui.node.workflow.NodeContainerUI;
import org.knime.core.ui.node.workflow.WorkflowManagerUI;
import org.knime.workbench.editor2.editparts.policy.NewWorkflowContainerEditPolicy;
import org.knime.workbench.editor2.editparts.policy.NewWorkflowXYLayoutPolicy;
import org.knime.workbench.editor2.editparts.snap.SnapIconToGrid;
import org.knime.workbench.editor2.editparts.snap.SnapToPortGeometry;
import org.knime.workbench.editor2.figures.ProgressToolTipHelper;
import org.knime.workbench.editor2.figures.WorkflowFigure;
import org.knime.workbench.editor2.figures.WorkflowLayout;
import org.knime.workbench.editor2.model.WorkflowPortBar;

/**
 * Root controller for the <code>WorkflowManager</code> model object. Consider
 * this as the controller for the "background" of the editor. It always has a
 * <code>WorkflowManager</code> as its model object.
 *
 * Model: {@link WorkflowManager}
 *
 *
 * @author Florian Georg, University of Konstanz
 */
public class WorkflowRootEditPart extends AbstractWorkflowEditPart implements
        WorkflowListener, CommandStackListener, ConnectableEditPart,
        // since annotations fire ui events:
        NodeUIInformationListener {
    /**
     * If passed to {@link #setFutureSelection(NodeID[])}, all newly created nodes will be selected after their
     * respective edit-parts have been created.
     */
    public static final NodeID[] ALL_NEW_NODES = new NodeID[0];

    /**
     * Same as for {@link #ALL_NEW_NODES} but for workflow annotations.
     */
    public static final Collection ALL_NEW_ANNOTATIONS = new ArrayList(0);

    private static final NodeLogger LOGGER = NodeLogger
            .getLogger(WorkflowRootEditPart.class);

    private ProgressToolTipHelper m_toolTipHelper;

    private WorkflowPortBar m_inBar;

    private WorkflowPortBar m_outBar;

    // TODO: maybe also connections, workflow ports, etc, should be stored
    /*
     * This stores the node ids from the PasteAction. If the NodeContainer with
     * the referring NodeID is created through createChild, the referring
     * EditPart is selected and removed from this set. If this set is empty, the
     * selection is cleared before the new EditPart is added (normal addition of
     * nodes).
     */
    private final Set<NodeID> m_futureSelection = new LinkedHashSet<NodeID>();

    private boolean m_futureSelectionAllNewNodes = false;
    private boolean m_futureSelectionAllNewAnnotations = false;

    /* same deal for added annotations */
    private final Set<WorkflowAnnotation> m_annotationSelection =
            new LinkedHashSet<WorkflowAnnotation>();

    /**
     * @return The <code>WorkflowManager</code> that is used as model for this
     *         edit part
     */
    public WorkflowManagerUI getWorkflowManager() {
        return (WorkflowManagerUI)getModel();
    }

    /**
     * Sets the NodeIDs from a set of nodes that are added to the editor and
     * should be selected as soon as they appear.
     *
     * If {@link #ALL_NEW_NODES} is passed, all newly added nodes are selected.
     *
     * @param ids node ids of the {@link NodeContainerEditPart}s that should be
     *            selected as soon as their {@link NodeContainerEditPart}s are
     *            created.
     */
    public void setFutureSelection(final NodeID[] ids) {
        m_futureSelection.clear();
        if (ids == ALL_NEW_NODES) {
            m_futureSelectionAllNewNodes = true;
        } else {
            m_futureSelectionAllNewNodes = false;
            for (NodeID id : ids) {
                m_futureSelection.add(id);
            }
        }
    }

    /**
     * Sets the annotations that are added to the editor and that should be
     * selected as soon as they appear.
     *
     * If {@link #ALL_NEW_ANNOTATIONS} is passed, all newly created annotations are selected.
     *
     * @param annos the workflow annotations to be selected after they have been
     *            created
     */
    public void setFutureAnnotationSelection(
            final Collection<WorkflowAnnotation> annos) {
        m_annotationSelection.clear();
        if (annos == ALL_NEW_ANNOTATIONS) {
            m_futureSelectionAllNewAnnotations = true;
        } else {
            m_futureSelectionAllNewAnnotations = false;
            m_annotationSelection.addAll(annos);
        }
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public NodeContainerUI getNodeContainer() {
        return getWorkflowManager();
    }

    /**
     * Returns the model chidlren, that is, the <code>NodeConatiner</code>s that
     * are stored in the workflow manager.
     *
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    protected List getModelChildren() {
        List modelChildren = new ArrayList();
        WorkflowManagerUI wfm = getWorkflowManager();
        // sequence here determines z-order of edit parts

        // Add workflow annotations as children of the workflow manager.
        // Add them first so they appear behind everything else
        for (Annotation anno : wfm.getWorkflowAnnotations()) {
            modelChildren.add(anno);
        }
        // Add the annotations associated with nodes (add them after the
        // workflow annotations so they appear above them)
        for (NodeAnnotation nodeAnno : wfm.getNodeAnnotations()) {
            modelChildren.add(nodeAnno);
        }

        modelChildren.addAll(wfm.getNodeContainers());
        if (wfm.getNrWorkflowIncomingPorts() > 0) {
            if (m_inBar == null) {
                m_inBar = new WorkflowPortBar(wfm, true);
                NodeUIInformation uiInfo =
                        wfm.getInPortsBarUIInfo();
                if (uiInfo != null) {
                    m_inBar.setUIInfo(wfm
                            .getInPortsBarUIInfo());
                }
            }
            modelChildren.add(m_inBar);
        }
        if (wfm.getNrWorkflowOutgoingPorts() > 0) {
            if (m_outBar == null) {
                m_outBar = new WorkflowPortBar(wfm, false);
                NodeUIInformation uiInfo =
                        wfm.getOutPortsBarUIInfo();
                if (uiInfo != null) {
                    m_outBar.setUIInfo(wfm.getOutPortsBarUIInfo());
                }
            }
            modelChildren.add(m_outBar);
        }
        return modelChildren;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getAdapter(final Class adapter) {
        if (adapter == SnapToHelper.class) {
            List<SnapToHelper> snapStrategies = new ArrayList<SnapToHelper>();
            Boolean ruler =
                    (Boolean)getViewer().getProperty(
                            RulerProvider.PROPERTY_RULER_VISIBILITY);
            if (ruler != null && ruler.booleanValue()) {
                snapStrategies.add(new SnapToGuides(this));
            }

            Boolean snapToGrid = (Boolean)getViewer().getProperty(SnapToGrid.PROPERTY_GRID_ENABLED);
            if (snapToGrid != null && snapToGrid.booleanValue()) {
				snapStrategies.add(new SnapIconToGrid(this));
			} else {
				// snap to ports
				snapStrategies.add(new SnapToPortGeometry(this));
			}
            if (snapStrategies.size() == 0) {
                return null;
            }
            if (snapStrategies.size() == 1) {
                return snapStrategies.get(0);
            }

            SnapToHelper[] ss = new SnapToHelper[snapStrategies.size()];
            for (int i = 0; i < snapStrategies.size(); i++) {
                ss[i] = snapStrategies.get(i);
            }
            return new CompoundSnapToHelper(ss);
        }
        return super.getAdapter(adapter);
    }

    /**
     * Activate controller, register as workflow listener.
     *
     * {@inheritDoc}
     */
    @Override
    public void activate() {
        super.activate();
        // register as listener on model object
        getWorkflowManager().addListener(this);

        // add as listener on the command stack
        getViewer().getEditDomain().getCommandStack()
                .addCommandStackListener(this);
    }

    /**
     * Deactivate controller.
     *
     * {@inheritDoc}
     */
    @Override
    public void deactivate() {
        LOGGER.debug("WorkflowRootEditPart deactivated");
        for (Object o : getChildren()) {
            EditPart editPart = (EditPart)o;
            editPart.deactivate();
        }
        getWorkflowManager().removeListener(this);
        getViewer().getEditDomain().getCommandStack()
                .removeCommandStackListener(this);
        EditPolicyIterator editPolicyIterator = getEditPolicyIterator();
        while (editPolicyIterator.hasNext()) {
            editPolicyIterator.next().deactivate();
        }
        super.deactivate();
    }

    /**
     * Creates the root(="background") figure and sets the appropriate lazout
     * manager.
     *
     * {@inheritDoc}
     */
    @Override
    protected WorkflowFigure createFigure() {

        WorkflowFigure backgroundFigure = new WorkflowFigure();

        LayoutManager l = new WorkflowLayout();
        backgroundFigure.setLayoutManager(l);

        return backgroundFigure;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WorkflowFigure getFigure() {
        return (WorkflowFigure)super.getFigure();
    }

    /**
     * This installes the edit policies for the root EditPart:
     * <ul>
     * <li><code>EditPolicy.CONTAINER_ROLE</code> - this serves as a container
     * for nodes</li>
     * <li><code>EditPolicy.LAYOUT_ROLE</code> - this edit part a layout that
     * allows children to be moved</li>.
     * </ul>
     *
     * {@inheritDoc}
     */
    @Override
    protected void createEditPolicies() {

        // install the CONTAINER_ROLE
        installEditPolicy(EditPolicy.CONTAINER_ROLE,
                new NewWorkflowContainerEditPolicy());

        // install the LAYOUT_ROLE
        installEditPolicy(EditPolicy.LAYOUT_ROLE,
                new NewWorkflowXYLayoutPolicy());

//        installEditPolicy(EditPolicy.SELECTION_FEEDBACK_ROLE, new WorkflowSelectionFeedbackPolicy());
    }

    private final AtomicBoolean m_workflowChangedOngoingBoolean = new AtomicBoolean();

    /**
     * Controller is getting notified about model changes. This invokes
     * <code>refreshChildren</code> keep in sync with the model.
     *
     * {@inheritDoc}
     */
    @Override
    public void workflowChanged(final WorkflowEvent event) {

        if (m_workflowChangedOngoingBoolean.compareAndSet(false, true)) {
            Display.getDefault().asyncExec(new Runnable() {
                @Override
                public void run() {
                    m_workflowChangedOngoingBoolean.set(false);

                    // refreshing the children
                    refreshChildren();

                    // refresing connections
                    refreshSourceConnections();
                    refreshTargetConnections();

                    // update out port (workflow in port) tooltips

                    for (Object part : getChildren()) {

                        if (part instanceof NodeOutPortEditPart
                                || part instanceof WorkflowInPortEditPart) {
                            AbstractPortEditPart outPortPart =
                                    (AbstractPortEditPart)part;
                            outPortPart.rebuildTooltip();
                        }
                    }

                    // always refresh visuals
                    getFigure().revalidate();
                    refreshVisuals();
                }
            });
        }
    }

    private final AtomicBoolean m_nodeUIChangedOngoingBoolean = new AtomicBoolean();

    /**
     * Called by the workflow manager after workflow annotations change.
     * {@inheritDoc}
     */
    @Override
    public void nodeUIInformationChanged(final NodeUIInformationEvent evt) {
        if (m_nodeUIChangedOngoingBoolean.compareAndSet(false, true)) {
            Display.getDefault().asyncExec(new Runnable() {
                @Override
                public void run() {
                    m_nodeUIChangedOngoingBoolean.set(false);
                    // annotations are children of the workflow
                    refreshChildren();
                    // always refresh visuals
                    getFigure().revalidate();
                    refreshVisuals();
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void commandStackChanged(final EventObject event) {
        // not doing anything
    }

    /**
     * @return the tooltip helper for this workflow part
     */
    public ProgressToolTipHelper getToolTipHelper() {
        return m_toolTipHelper;
    }

    /**
     *
     * @param underlyingShell underlying shell
     */
    public void createToolTipHelper(final Shell underlyingShell) {
        // create a tooltip helper for all child figures
        ZoomManager zoomManager =
                (ZoomManager)(getRoot().getViewer()
                        .getProperty(ZoomManager.class.toString()));
        m_toolTipHelper =
                new ProgressToolTipHelper(getViewer().getControl(), zoomManager);
        getFigure().setProgressToolTipHelper(m_toolTipHelper);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void refreshVisuals() {
        ConnectionLayer cLayer =
                (ConnectionLayer) getLayer(LayerConstants.CONNECTION_LAYER);
        if (cLayer != null) {
            EditPartViewer viewer = getViewer();
            if (viewer != null) {
                Control c = viewer.getControl();
                if ((c != null) && ((c.getStyle() & SWT.MIRRORED) == 0)) {
                    cLayer.setAntialias(SWT.ON);
                }
            }
        }
        super.refreshVisuals();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    protected EditPart createChild(final Object model) {
        final EditPart part = super.createChild(model);
        LOGGER.debug("part: " + part);
        if (part instanceof NodeContainerEditPart) {
            getViewer().deselect(this);
            NodeID id =
                    ((NodeContainerEditPart)part).getNodeContainer().getID();
            if (m_futureSelectionAllNewNodes) {
                getViewer().appendSelection(part);
                revealPart(part);
            } else if (m_futureSelection.isEmpty()) {
                // select only this element
                getViewer().deselectAll();
                getViewer().select(part);
            } else if (m_futureSelection.contains(id)) {
                // append this element to the current selection
                getViewer().appendSelection(part);
                m_futureSelection.remove(id);
                // reveal the editpart after it has been created completely
                revealPart(part);
            }
        }
        if (model instanceof Annotation) {
            // newly created annotations are only selected if done explicitly
            getViewer().deselect(this);
            if (m_futureSelectionAllNewAnnotations) {
                getViewer().appendSelection(part);
                revealPart(part);
            } else if (m_annotationSelection.contains(model)) {
                getViewer().appendSelection(part);
                m_annotationSelection.remove(model);
                // reveal the editpart after it has been created completely
                revealPart(part);
            }
        }
        // connections are selected in workflowChanged
        return part;
    }

    private void revealPart(final EditPart part) {
        Display.getCurrent().asyncExec(new Runnable() {
            @Override
            public void run() {
                getViewer().reveal(part);
            }
        });
    }

    /** true, if node names are hidden, otherwise false - default. */
    private boolean m_hideNodeNames = false;

    /** true, if node ids are appended to the node name, otherwise false - default. */
    private boolean m_showNodeID = false;

    /** @return change show/hide node label status */
    public boolean changeHideNodeNames() {
        m_hideNodeNames = !m_hideNodeNames;
        return m_hideNodeNames;
    }

    /** @return change show/hide node label status */
    public boolean changeShowNodeID() {
        m_showNodeID = !m_showNodeID;
        return m_showNodeID;
    }

    /** @return true, if node labels are hidden, otherwise false */
    public boolean hideNodeNames() {
        return m_hideNodeNames;
    }

    /** @return true, if node ids should be appended to the node name, otherwise false */
    public boolean showNodeId() {
        return m_showNodeID;
    }
}
