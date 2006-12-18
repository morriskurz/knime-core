/* -------------------------------------------------------------------
 * This source code, its documentation and all appendant files
 * are protected by copyright law. All rights reserved.
 * 
 * Copyright, 2003 - 2006
 * Universitaet Konstanz, Germany.
 * Lehrstuhl fuer Angewandte Informatik
 * Prof. Dr. Michael R. Berthold
 * 
 * You may not modify, publish, transmit, transfer or sell, reproduce,
 * create derivative works from, distribute, perform, display, or in
 * any way exploit any of the content, in whole or in part, except as
 * otherwise expressly permitted in writing by the copyright owner.
 * -------------------------------------------------------------------
 * 
 * History
 *   29.09.2006 (Fabian Dill): created
 */
package org.knime.base.node.viz.plotter.box;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JCheckBox;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.knime.base.node.viz.plotter.basic.BasicPlotter;
import org.knime.base.node.viz.plotter.scatter.DotInfo;
import org.knime.base.node.viz.plotter.scatter.DotInfoArray;
import org.knime.base.util.coordinate.Coordinate;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DoubleValue;
import org.knime.core.data.RowKey;
import org.knime.core.data.def.DoubleCell;
import org.knime.core.data.def.StringCell;
import org.knime.core.data.property.ColorAttr;
import org.knime.core.data.property.ShapeFactory;
import org.knime.core.node.property.hilite.KeyEvent;

/**
 * The <code>BoxPlotter</code> calculates, based on the statistical 
 * parameters determined by the 
 * {@link org.knime.base.node.viz.plotter.box.BoxPlotNodeModel}, the 
 * {@link org.knime.base.node.viz.plotter.box.Box}es to 
 * draw in the <code>updateSize</code> method. The drawable box is represented 
 * by a {@link org.knime.base.node.viz.plotter.box.Box} which 
 * holds the mapped value for each statistcal parameter and its x position. The 
 * mild and extreme outliers are set as a 
 * {@link org.knime.base.node.viz.plotter.scatter.DotInfoArray} used from the 
 * {@link org.knime.base.node.viz.plotter.scatter.ScatterPlotter}.
 * 
 * @author Fabian Dill, University of Konstanz
 */
public class BoxPlotter extends BasicPlotter {
    
    
    /** Constant for the space at top and bottom. */
    public static final int OFFSET = 30;
    
    private boolean m_normalize;
    
    private Map<DataColumnSpec, Coordinate>m_coordinates;
    
    private Set<String> m_selectedColumns;
    
    /**
     * 
     *
     */
    public BoxPlotter() {
        super(new BoxPlotDrawingPane(), new BoxPlotterProperties());
        final JCheckBox box = ((BoxPlotterProperties)getProperties())
            .getNormalizeCheckBox();
        box.addItemListener(new ItemListener() {
            /**
             * @see java.awt.event.ItemListener#itemStateChanged(
             * java.awt.event.ItemEvent)
             */
            public void itemStateChanged(final ItemEvent e) {
                m_normalize = box.isSelected();
                updatePaintModel();
                fitToScreen();
            }
        });
        ((BoxPlotterProperties)getProperties()).getColumnFilter()
            .addChangeListener(new ChangeListener() {
                /**
                 * @see javax.swing.event.ChangeListener#stateChanged(
                 * javax.swing.event.ChangeEvent)
                 */
                public void stateChanged(final ChangeEvent e) {
                    m_selectedColumns = ((BoxPlotterProperties)getProperties())
                        .getColumnFilter().getIncludedColumnSet();
                    updatePaintModel();
                    getDrawingPane().repaint();
                }
            });
        if (getDataProvider() != null 
                && getDataProvider().getDataArray(0) != null
                && getDataProvider().getDataArray(0)
                .getDataTableSpec() != null) {
            ((BoxPlotterProperties)getProperties()).getColumnFilter().update(
                getDataProvider().getDataArray(0).getDataTableSpec(),
                true, m_selectedColumns);
        }
    }
    
    
    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter#reset()
     */
    @Override
    public void reset() {
        m_coordinates = null;
        m_selectedColumns = null;
        ((BoxPlotDrawingPane)getDrawingPane()).setBoxes(null);
        getDrawingPane().repaint();
    }

    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter#updatePaintModel()
     */
    @Override
    public void updatePaintModel() {
        if (getDataProvider() == null 
                || ((BoxPlotDataProvider)getDataProvider())
                    .getStatistics() == null) {
            m_selectedColumns = null;
            return;
        }
        Map<DataColumnSpec, double[]> statistics = ((BoxPlotDataProvider)
                getDataProvider()).getStatistics();
        DataTableSpec tableSpec = getDataProvider().getDataArray(0)
            .getDataTableSpec();
        if (m_selectedColumns == null) {
            m_selectedColumns = new LinkedHashSet<String>();
            for (DataColumnSpec colName : tableSpec) {
                if (colName.getType().isCompatible(DoubleValue.class)) {
                    m_selectedColumns.add(colName.getName());
                }
            }
        }
        ((BoxPlotterProperties)getProperties()).updateColumnSelection(
                tableSpec, m_selectedColumns);
//        m_selectedColumns = ((BoxPlotterProperties)getProperties())
//            .getColumnFilter().getIncludedColumnList();
        Set<DataCell>columns = new LinkedHashSet<DataCell>();
        for (String colName : m_selectedColumns) {
            int colIdx = tableSpec.findColumnIndex(colName);
            if (colIdx >= 0) {
                columns.add(new StringCell(colName));                
            }
        }
        createNominalXCoordinate(columns);
        if (!m_normalize) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (Map.Entry<DataColumnSpec, double[]> entry : statistics
                    .entrySet()) {
                if (!m_selectedColumns.contains(entry.getKey().getName())) {
                    continue;
                }
                double[] stats = entry.getValue();
                min = Math.min(min, stats[BoxPlotNodeModel.MIN]);
                max = Math.max(max, stats[BoxPlotNodeModel.MAX]);
            }
            setPreserve(false);
            if (m_selectedColumns.size() > 0) {
                createYCoordinate(min, max);
            } else {
                // hack to achieve an empty y axis
                createNominalYCoordinate(new LinkedHashSet<DataCell>()); 
            }
        } else {
            m_coordinates = new LinkedHashMap<DataColumnSpec, Coordinate>();
            for (DataColumnSpec colSpec : statistics.keySet()) {
                m_coordinates.put(colSpec, 
                        Coordinate.createCoordinate(colSpec));
            }
            // hack to achieve an empty y axis
            createNominalYCoordinate(new LinkedHashSet<DataCell>());
        }
        ((BoxPlotterProperties)getProperties()).getColumnFilter().update(
                getDataProvider().getDataArray(0).getDataTableSpec(),
                false, m_selectedColumns);
        updateSize();
    }

    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter#updateSize()
     */
    @Override
    public void updateSize() {
        if (getDataProvider() == null 
                || ((BoxPlotDataProvider)getDataProvider())
                    .getStatistics() == null) {
            return;
        }
        if (m_selectedColumns == null) {
            return;
        }
        Map<DataColumnSpec, double[]> statistics = ((BoxPlotDataProvider)
                getDataProvider()).getStatistics();
        List<Box> boxes = new ArrayList<Box>();
        List<DotInfo> outliers = new ArrayList<DotInfo>();
        for (Map.Entry<DataColumnSpec, double[]> entry : statistics
                .entrySet()) {
            Coordinate yCoordinate;
            if (!m_selectedColumns.contains(entry.getKey().getName())) {
                continue;
            }
            if (m_normalize) {
                yCoordinate = m_coordinates.get(entry.getKey());
            } else {
                if (getYAxis() == null) {
                    updatePaintModel();
                }
                yCoordinate = getYAxis().getCoordinate();
                getYAxis().setStartTickOffset(OFFSET / 2);
            }
            String colName = entry.getKey().getName();
            double[] stats = entry.getValue();
            int x = (int)getXAxis().getCoordinate().calculateMappedValue(
                    new StringCell(colName), getDrawingPaneDimension().width, 
                    true);
            int height = getDrawingPaneDimension().height - OFFSET;
            int yMin = (int)getScreenYCoordinate(yCoordinate
                    .calculateMappedValue(
                    new DoubleCell(stats[BoxPlotNodeModel.MIN]), 
                    height, true)); 
            int yLowQuart = (int)getScreenYCoordinate(yCoordinate
                    .calculateMappedValue(
                    new DoubleCell(stats[BoxPlotNodeModel.LOWER_QUARTILE]), 
                    height, true));
            int yMed = (int)getScreenYCoordinate(yCoordinate
                    .calculateMappedValue(
                    new DoubleCell(stats[BoxPlotNodeModel.MEDIAN]), 
                    height, true));
            int yUppQuart = (int)getScreenYCoordinate(yCoordinate
                    .calculateMappedValue(
                    new DoubleCell(stats[BoxPlotNodeModel.UPPER_QUARTILE]), 
                    height, true));
            int yMax = (int)getScreenYCoordinate(yCoordinate
                    .calculateMappedValue(
                    new DoubleCell(stats[BoxPlotNodeModel.MAX]), 
                    height, true));
            Box box = new Box(x, yMin - (OFFSET / 2), yLowQuart - (OFFSET / 2), 
                    yMed - (OFFSET / 2), yUppQuart - (OFFSET / 2), 
                    yMax - (OFFSET / 2), stats);
            box.setColumnName(entry.getKey().getName());
            // whiskers
            int lowerWhisker = (int)getScreenYCoordinate(
                    yCoordinate.calculateMappedValue(
                            new DoubleCell(
                            stats[BoxPlotNodeModel.LOWER_WHISKER]), 
                            height, true));
            int upperWhisker = (int)getScreenYCoordinate(
                    yCoordinate.calculateMappedValue(
                            new DoubleCell(
                            stats[BoxPlotNodeModel.UPPER_WHISKER]), 
                            height, true));
            box.setLowerWhiskers(lowerWhisker - (OFFSET / 2));
            box.setUpperWhiskers(upperWhisker - (OFFSET / 2));
            boxes.add(box);
            outliers.addAll(updateOutliers(yCoordinate, box));
        }
        ((BoxPlotDrawingPane)getDrawingPane()).setBoxes(boxes);
        DotInfo[] dots = new DotInfo[outliers.size()];
        outliers.toArray(dots);
        ((BoxPlotDrawingPane)getDrawingPane()).setDotInfoArray(
                new DotInfoArray(dots));
        
        if (getXAxis() != null && getXAxis().getCoordinate() != null) {
            int boxWidth = (int)getXAxis().getCoordinate()
            .getUnusedDistBetweenTicks(getDrawingPaneDimension().width);
            boxWidth = boxWidth / 4;
            ((BoxPlotDrawingPane)getDrawingPane()).setBoxWidth(boxWidth);
        }
        getDrawingPane().repaint();
    }
    
    /**
     * Sets the outliers as dotinfo to the scatterplotter drawing pane to
     * make them selectable and hilite-able.
     * @param yCoordinate the corresponding y coordinate.
     * @param box the box (column).
     * @return the mapped outliers for this column.
     */
    protected List<DotInfo> updateOutliers(final Coordinate yCoordinate, 
            final Box box) {
        int height = getDrawingPaneDimension().height - OFFSET;
        List<DotInfo> dotList = new ArrayList<DotInfo>();
            int x = box.getX();
            String colName = box.getColumnName();
            // the mild outliers
            Map<Double, RowKey> mildOutliers 
                = ((BoxPlotDataProvider)getDataProvider()).getMildOutliers()
                .get(colName);
            for (Map.Entry<Double, RowKey> entry : mildOutliers.entrySet()) {
                double value = entry.getKey();
                RowKey key = entry.getValue();
                int y = (int)getScreenYCoordinate(
                        yCoordinate.calculateMappedValue(new DoubleCell(value),
                                height, true)) - (OFFSET / 2);
                DotInfo dot = new DotInfo(x, y, key, 
                        delegateIsHiLit(key.getId()), ColorAttr.DEFAULT, 0, 
                        0);
                dot.setXDomainValue(new StringCell(colName));
                dot.setYDomainValue(new DoubleCell(value));
                dot.setShape(ShapeFactory.getShape(ShapeFactory.CIRCLE));
                dotList.add(dot);
            }
            // the extreme outliers
            Map<Double, RowKey> extremeOutliers 
                = ((BoxPlotDataProvider)getDataProvider()).getExtremeOutliers()
                .get(colName);
            for (Map.Entry<Double, RowKey> entry : extremeOutliers.entrySet()) {
                double value = entry.getKey();
                RowKey key = entry.getValue();
                int y = (int)getScreenYCoordinate(
                        yCoordinate.calculateMappedValue(new DoubleCell(value),
                                height, true)) - (OFFSET / 2);
                DotInfo dot = new DotInfo(x, y, key, 
                        delegateIsHiLit(key.getId()), ColorAttr.DEFAULT, 0, 
                        0);
                dot.setShape(ShapeFactory.getShape(ShapeFactory.CROSS));
                dot.setXDomainValue(new StringCell(colName));
                dot.setYDomainValue(new DoubleCell(value));
                dotList.add(dot);
            }
            return dotList;
    }
    
    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter#clearSelection()
     */
    @Override
    public void clearSelection() {
        ((BoxPlotDrawingPane)getDrawingPane()).clearSelection();
    }

    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter#hiLite(
     * org.knime.core.node.property.hilite.KeyEvent)
     */
    @Override
    public void hiLite(final KeyEvent event) {
        // the hilite handler holds the keys
        // in the update outliers the hilite handler is asked directly if a 
        // dot is hilited or not -> update paint model is enough.
        updatePaintModel();
    }

    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter#hiLiteSelected()
     */
    @Override
    public void hiLiteSelected() {
        delegateHiLite(((BoxPlotDrawingPane)getDrawingPane())
                .getSelectedDots());
        updatePaintModel();
    }

    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter
     * #selectClickedElement(java.awt.Point)
     */
    @Override
    public void selectClickedElement(final Point clicked) {
        ((BoxPlotDrawingPane)getDrawingPane()).selectClickedElement(clicked);
    }

    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter
     * #selectElementsIn(java.awt.Rectangle)
     */
    @Override
    public void selectElementsIn(final Rectangle selectionRectangle) {
        ((BoxPlotDrawingPane)getDrawingPane()).selectElementsIn(
                selectionRectangle.x, selectionRectangle.y,
                selectionRectangle.x + selectionRectangle.width,
                selectionRectangle.y + selectionRectangle.height);
    }

    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter#unHiLite(
     * org.knime.core.node.property.hilite.KeyEvent)
     */
    @Override
    public void unHiLite(final KeyEvent event) {
        // the hilite handler holds the keys
        // in the update outliers the hilite handler is asked directly if a 
        // dot is hilited or not -> update paint model is enough.
        updatePaintModel();
    }

    /**
     * @see org.knime.base.node.viz.plotter.AbstractPlotter#unHiLiteSelected()
     */
    @Override
    public void unHiLiteSelected() {
        delegateUnHiLite(((BoxPlotDrawingPane)getDrawingPane())
                .getSelectedDots());
        updatePaintModel();
    }    

    /**
     * @see org.knime.core.node.property.hilite.HiLiteListener#unHiLiteAll()
     */
    public void unHiLiteAll() {
        updatePaintModel();
    }

}
