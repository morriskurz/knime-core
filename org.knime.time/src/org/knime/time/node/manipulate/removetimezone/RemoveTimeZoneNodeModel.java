/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME GmbH, Konstanz, Germany
 *  Website: http://www.knime.org; Email: contact@knime.org
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
 *  KNIME and ECLIPSE being a combined program, KNIME GMBH herewith grants
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
 * ---------------------------------------------------------------------
 *
 * History
 *   Oct 28, 2016 (simon): created
 */
package org.knime.time.node.manipulate.removetimezone;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.knime.base.data.replace.ReplacedColumnsDataRow;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.append.AppendedColumnRow;
import org.knime.core.data.container.ColumnRearranger;
import org.knime.core.data.container.SingleCellFactory;
import org.knime.core.data.time.localdatetime.LocalDateTimeCellFactory;
import org.knime.core.data.time.zoneddatetime.ZonedDateTimeCell;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeModel;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.defaultnodesettings.SettingsModelColumnFilter2;
import org.knime.core.node.defaultnodesettings.SettingsModelString;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.streamable.InputPortRole;
import org.knime.core.node.streamable.OutputPortRole;
import org.knime.core.node.streamable.PartitionInfo;
import org.knime.core.node.streamable.PortInput;
import org.knime.core.node.streamable.PortOutput;
import org.knime.core.node.streamable.RowInput;
import org.knime.core.node.streamable.RowOutput;
import org.knime.core.node.streamable.StreamableOperator;
import org.knime.core.util.UniqueNameGenerator;

/**
 * The node model of the node which removes the time zone.
 *
 * @author Simon Schmid, KNIME.com, Konstanz, Germany
 */
class RemoveTimeZoneNodeModel extends NodeModel {

    private final SettingsModelColumnFilter2 m_colSelect = RemoveTimeZoneNodeDialog.createColSelectModel();

    private final SettingsModelString m_isReplaceOrAppend = RemoveTimeZoneNodeDialog.createReplaceAppendStringBool();

    private final SettingsModelString m_suffix = RemoveTimeZoneNodeDialog.createSuffixModel();

    /**
     * one in, one out
     */
    protected RemoveTimeZoneNodeModel() {
        super(1, 1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected DataTableSpec[] configure(final DataTableSpec[] inSpecs) throws InvalidSettingsException {
        final ColumnRearranger columnRearranger = createColumnRearranger(inSpecs[0]);
        return new DataTableSpec[]{columnRearranger.createSpec()};
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected BufferedDataTable[] execute(final BufferedDataTable[] inData, final ExecutionContext exec)
        throws Exception {
        final ColumnRearranger columnRearranger = createColumnRearranger(inData[0].getDataTableSpec());
        final BufferedDataTable out = exec.createColumnRearrangeTable(inData[0], columnRearranger, exec);
        return new BufferedDataTable[]{out};
    }

    /**
     * @param inSpec table input spec
     * @return the CR describing the output
     */
    private ColumnRearranger createColumnRearranger(final DataTableSpec inSpec) {
        final ColumnRearranger rearranger = new ColumnRearranger(inSpec);
        final String[] includeList = m_colSelect.applyTo(inSpec).getIncludes();
        final int[] includeIndeces =
            Arrays.stream(m_colSelect.applyTo(inSpec).getIncludes()).mapToInt(s -> inSpec.findColumnIndex(s)).toArray();
        int i = 0;

        for (String includedCol : includeList) {
            if (m_isReplaceOrAppend.getStringValue().equals(RemoveTimeZoneNodeDialog.OPTION_REPLACE)) {
                DataColumnSpecCreator dataColumnSpecCreator =
                    new DataColumnSpecCreator(includedCol, LocalDateTimeCellFactory.TYPE);
                RemoveTimeZoneCellFactory cellFac =
                    new RemoveTimeZoneCellFactory(dataColumnSpecCreator.createSpec(), includeIndeces[i++]);
                rearranger.replace(cellFac, includedCol);
            } else {
                DataColumnSpec dataColSpec = new UniqueNameGenerator(inSpec)
                    .newColumn(includedCol + m_suffix.getStringValue(), LocalDateTimeCellFactory.TYPE);
                RemoveTimeZoneCellFactory cellFac = new RemoveTimeZoneCellFactory(dataColSpec, includeIndeces[i++]);
                rearranger.append(cellFac);
            }
        }
        return rearranger;
    }

    /** {@inheritDoc} */
    @Override
    public InputPortRole[] getInputPortRoles() {
        return new InputPortRole[]{InputPortRole.DISTRIBUTED_STREAMABLE};
    }

    /** {@inheritDoc} */
    @Override
    public OutputPortRole[] getOutputPortRoles() {
        return new OutputPortRole[]{OutputPortRole.DISTRIBUTED};
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamableOperator createStreamableOperator(final PartitionInfo partitionInfo,
        final PortObjectSpec[] inSpecs) throws InvalidSettingsException {
        return new StreamableOperator() {
            @Override
            public void runFinal(final PortInput[] inputs, final PortOutput[] outputs, final ExecutionContext exec)
                throws Exception {
                RowInput in = (RowInput)inputs[0];
                RowOutput out = (RowOutput)outputs[0];
                DataTableSpec inSpec = in.getDataTableSpec();
                String[] includeList = m_colSelect.applyTo(inSpec).getIncludes();
                int[] includeIndices = Arrays.stream(m_colSelect.applyTo(inSpec).getIncludes())
                    .mapToInt(s -> inSpec.findColumnIndex(s)).toArray();
                boolean isReplace =
                    m_isReplaceOrAppend.getStringValue().equals(RemoveTimeZoneNodeDialog.OPTION_REPLACE);
                RemoveTimeZoneCellFactory[] cellFacs = new RemoveTimeZoneCellFactory[includeIndices.length];

                if (isReplace) {
                    for (int i = 0; i < includeIndices.length; i++) {
                        final DataColumnSpecCreator dataColumnSpecCreator =
                            new DataColumnSpecCreator(includeList[i], LocalDateTimeCellFactory.TYPE);
                        cellFacs[i] =
                            new RemoveTimeZoneCellFactory(dataColumnSpecCreator.createSpec(), includeIndices[i]);
                    }
                } else {
                    for (int i = 0; i < includeIndices.length; i++) {
                        final DataColumnSpec dataColSpec = new UniqueNameGenerator(inSpec)
                            .newColumn(includeList[i] + m_suffix.getStringValue(), LocalDateTimeCellFactory.TYPE);
                        cellFacs[i] = new RemoveTimeZoneCellFactory(dataColSpec, includeIndices[i]);
                    }
                }

                DataRow row;
                while ((row = in.poll()) != null) {
                    exec.checkCanceled();
                    DataCell[] datacells = new DataCell[includeIndices.length];
                    for (int i = 0; i < includeIndices.length; i++) {
                        if (isReplace) {
                            datacells[i] = cellFacs[i].getCell(row);
                        } else {
                            datacells[i] = cellFacs[i].getCell(row);
                        }
                    }
                    if (isReplace) {
                        out.push(new ReplacedColumnsDataRow(row, datacells, includeIndices));
                    } else {
                        out.push(new AppendedColumnRow(row, datacells));
                    }
                }
                in.close();
                out.close();
            }
        };

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void loadInternals(final File nodeInternDir, final ExecutionMonitor exec)
        throws IOException, CanceledExecutionException {
        // no internals
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void saveInternals(final File nodeInternDir, final ExecutionMonitor exec)
        throws IOException, CanceledExecutionException {
        // no internals
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void saveSettingsTo(final NodeSettingsWO settings) {
        m_colSelect.saveSettingsTo(settings);
        m_isReplaceOrAppend.saveSettingsTo(settings);
        m_suffix.saveSettingsTo(settings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void validateSettings(final NodeSettingsRO settings) throws InvalidSettingsException {
        m_colSelect.validateSettings(settings);
        m_isReplaceOrAppend.validateSettings(settings);
        m_suffix.validateSettings(settings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void loadValidatedSettingsFrom(final NodeSettingsRO settings) throws InvalidSettingsException {
        m_colSelect.loadSettingsFrom(settings);
        m_isReplaceOrAppend.loadSettingsFrom(settings);
        m_suffix.loadSettingsFrom(settings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void reset() {
        // no internals
    }

    private final class RemoveTimeZoneCellFactory extends SingleCellFactory {
        private final int m_colIndex;

        RemoveTimeZoneCellFactory(final DataColumnSpec inSpec, final int colIndex) {
            super(inSpec);
            m_colIndex = colIndex;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public DataCell getCell(final DataRow row) {
            final DataCell cell = row.getCell(m_colIndex);
            if (cell.isMissing()) {
                return cell;
            }
            if (cell instanceof ZonedDateTimeCell) {
                return LocalDateTimeCellFactory.create(((ZonedDateTimeCell)cell).getZonedDateTime().toLocalDateTime());
            }
            throw new IllegalStateException("Unexpected data type: " + cell.getClass());
        }
    }
}
