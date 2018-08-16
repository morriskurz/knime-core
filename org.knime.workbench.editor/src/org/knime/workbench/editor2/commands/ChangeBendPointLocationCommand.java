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
 *   02.03.2006 (Christoph Sieb): created
 */
package org.knime.workbench.editor2.commands;

import org.eclipse.draw2d.geometry.Point;
import org.eclipse.gef.editparts.ZoomManager;
import org.knime.core.node.workflow.ConnectionUIInformation;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.ui.node.workflow.ConnectionContainerUI;
import org.knime.workbench.editor2.WorkflowEditor;
import org.knime.workbench.editor2.editparts.ConnectionContainerEditPart;

/**
 * GEF Command for changing the location of a <code>ConnectionContainer</code>
 * in the workflow. The bounds are stored into the <code>UIInformation</code>
 * object of the <code>ConnectionContainer</code>
 *
 * @author Christoph Sieb, University of Konstanz
 */
public class ChangeBendPointLocationCommand extends AbstractKNIMECommand {

    private final Point m_locationShift;

    /* We keep destination node and port instead of the ConnectionContainer
     * as field. This allows redo/undo to be performed even if the connection
     * was (temporarily) removed.
     */
    /** ID of the connection's destination node. */
    private final NodeID m_destNodeID;
    /** Port the connection leads to. */
    private final int m_destPort;
    private final ZoomManager m_zoomManager;

    /**
     * @param container The connection container to change
     * @param locationShift the values (x,y) to change the location of all
     *            bendpoints
     * @param zoomManager The zoom manager, if null, no adaption of the shift is performed.
     */
    public ChangeBendPointLocationCommand(
            final ConnectionContainerEditPart container,
            final Point locationShift, final ZoomManager zoomManager) {
        super(container.getWorkflowManager());
        m_zoomManager = zoomManager;
        m_locationShift = locationShift;
        m_destNodeID = container.getModel().getDest();
        m_destPort = container.getModel().getDestPort();
    }

    private ConnectionContainerUI getConnectionContainer() {
        if (m_destNodeID == null) {
            return null;
        }

        return getHostWFMUI().getIncomingConnectionFor(m_destNodeID, m_destPort);
    }

    /** {@inheritDoc} */
    @Override
    public boolean canExecute() {
        return super.canExecute() && getConnectionContainer() != null;
    }

    /**
     * Shift all bendpoints in positive shift direction.
     *
     * @see org.eclipse.gef.commands.Command#execute()
     */
    @Override
    public void execute() {
        changeBendpointsUIInfo(false);
    }

    /** {@inheritDoc} */
    @Override
    public boolean canUndo() {
        return canExecute();
    }

    /**
     * Shift all bendpoints in negative shift direction.
     *
     * @see org.eclipse.gef.commands.Command#undo()
     */
    @Override
    public void undo() {
        changeBendpointsUIInfo(true);
    }

    private void changeBendpointsUIInfo(final boolean shiftBack) {

        ConnectionContainerUI cc = getConnectionContainer();
        ConnectionUIInformation ui = cc.getUIInfo();
        if (ui == null) {
            return;
        }

        int[][] bendpoints = ui.getAllBendpoints();

        Point locationShift = m_locationShift.getCopy();

        if (m_zoomManager != null) {
            WorkflowEditor.adaptZoom(m_zoomManager, locationShift, false);
        }
        int length = bendpoints.length;
        int shiftX = shiftBack ? locationShift.x * -1 : locationShift.x;
        int shiftY = shiftBack ? locationShift.y * -1 : locationShift.y;

        ConnectionUIInformation.Builder newUIBuilder = ConnectionUIInformation.builder();
        for (int i = 0; i < length; i++) {

            // get old
            int x = ui.getBendpoint(i)[0];
            int y = ui.getBendpoint(i)[1];

            // set the new point
            newUIBuilder.addBendpoint(x + shiftX, y + shiftY, i);
        }

        // must set explicitly so that event is fired by container
        cc.setUIInfo(newUIBuilder.build());
    }
}
