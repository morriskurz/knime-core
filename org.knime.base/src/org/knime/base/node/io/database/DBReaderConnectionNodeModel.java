/*
 * -------------------------------------------------------------------
 * This source code, its documentation and all appendant files
 * are protected by copyright law. All rights reserved.
 *
 * Copyright, 2003 - 2007
 * University of Konstanz, Germany
 * Chair for Bioinformatics and Information Mining (Prof. M. Berthold)
 * and KNIME GmbH, Konstanz, Germany
 *
 * You may not modify, publish, transmit, transfer or sell, reproduce,
 * create derivative works from, distribute, perform, display, or in
 * any way exploit any of the content, in whole or in part, except as
 * otherwise expressly permitted in writing by the copyright owner or
 * as specified in the license file distributed with this product.
 *
 * If you have any questions please contact the copyright holder:
 * website: www.knime.org
 * email: contact@knime.org
 * -------------------------------------------------------------------
 * 
 * History
 *   21.08.2005 (gabriel): created
 */
package org.knime.base.node.io.database;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.knime.core.data.DataTableSpec;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.ModelContentWO;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeModel;
import org.knime.core.node.NodeSettings;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.config.ConfigWO;
import org.knime.core.util.KnimeEncryption;

/**
 * 
 * @author Thomas Gabriel, University of Konstanz
 */
class DBReaderConnectionNodeModel extends NodeModel {

    private static final NodeLogger LOGGER =
            NodeLogger.getLogger(DBReaderConnectionNodeModel.class);

    private String m_driver;
    private String m_name;
    private String m_query = null;
    private String m_user = null;
    private String m_pass = null;

    /**
     * The name of the view created on the database defined by the given query.
     */
    private String m_viewName;

    /**
     * Creates a new database reader.
     * 
     * @param dataIn data ins
     * @param dataOut data outs
     * @param modelIn model ins
     * @param modelOut models outs
     */
    DBReaderConnectionNodeModel(final int dataIn, final int dataOut,
            final int modelIn, final int modelOut) {
        super(dataIn, dataOut, modelIn, modelOut);
        // init default driver with the first from the driver list
        // or use Java JDBC-ODBC as default
        m_driver = DBDriverLoader.JDBC_ODBC_DRIVER;
        String[] history = DBReaderDialogPane.DRIVER_ORDER.getHistory();
        if (history != null && history.length > 0) {
            m_driver = history[0];
        }
        // create database name from driver class
        m_name = DBDriverLoader.getURLForDriver(m_driver);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void saveModelContent(final int index,
            final ModelContentWO predParams) throws InvalidSettingsException {
        saveConfig(predParams);

        // additionally save the view name
        predParams.addString("view", m_viewName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void saveSettingsTo(final NodeSettingsWO settings) {
        saveConfig(settings);
    }

    private void saveConfig(final ConfigWO settings) {
        settings.addString("driver", m_driver);
        settings.addString("statement", m_query);
        settings.addString("database", m_name);
        settings.addString("user", m_user);
        settings.addString("password", m_pass);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void validateSettings(final NodeSettingsRO settings)
            throws InvalidSettingsException {
        loadSettings(settings, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void loadValidatedSettingsFrom(final NodeSettingsRO settings)
            throws InvalidSettingsException {
        loadSettings(settings, true);
    }

    private void loadSettings(final NodeSettingsRO settings, 
            final boolean write) throws InvalidSettingsException {
        String driver = settings.getString("driver");
        String statement = settings.getString("statement");
        if (statement == null || !statement.toLowerCase().contains("select")) {
            throw new InvalidSettingsException(
                    "SQL query invalid: " + statement);
        }
        String database = settings.getString("database");
        String user = settings.getString("user");
        // password
        String password = settings.getString("password", "");
        // loaded driver: need to load settings before 1.2
        String[] loadedDriver = settings.getStringArray("loaded_driver", 
                new String[0]);
        // write settings or skip it
        if (write) {
            m_driver = driver;
            DBReaderDialogPane.DRIVER_ORDER.add(m_driver);
            m_query = statement;
            boolean changed = false;
            if (m_user != null && m_name != null && m_pass != null) { 
                if (!user.equals(m_user) || !database.equals(m_name)
                        || !password.equals(m_pass)
                        || !statement.equals(m_query)) {
                    changed = true;
                }
            }
            m_name = database;
            DBReaderDialogPane.DRIVER_URLS.add(m_name);
            m_user = user;
            m_pass = password;
            for (String fileName : loadedDriver) {
                try {
                    DBDriverLoader.loadDriver(new File(fileName));
                } catch (Exception e2) {
                    LOGGER.info("Could not load driver: " + fileName, e2);
                }
            }
            if (changed) {
                connectionChanged();
            }
        }
    }
    
    /**
     * Called when the connection settings have changed, that are, database
     * name, user name, and password.
     */
    protected void connectionChanged() {
        
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected BufferedDataTable[] execute(final BufferedDataTable[] inData,
            final ExecutionContext exec) throws CanceledExecutionException,
            Exception {

        // create a view on the database according to the given select
        // statement
        // create a unique view name
        m_viewName = "VIEW_" + Long.toString(System.currentTimeMillis());
        String viewCreateSQL = "CREATE VIEW " + m_viewName 
            + " AS " + m_query;
        try {
            execute(viewCreateSQL);
        } catch (Exception e) {
            throw e;
        }
        return new BufferedDataTable[0];
    }
    
    private Exception execute(final String statement) {
        if (m_name == null || m_user == null || m_pass == null) {
            return new InvalidSettingsException("No settings available "
                    + "to create database connection.");
        }
        Connection conn = null;
        try {
            DBDriverLoader.registerDriver(getDriver());
            String password = KnimeEncryption.decrypt(m_pass);
            DriverManager.setLoginTimeout(5);
            conn = DriverManager.getConnection(m_name, m_user, password);
            Statement stmt = conn.createStatement();
            stmt.execute(statement);
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException sqle) {
                    return sqle;
                }
            }
            return e;
        }
        return null;
    }

    /**
     * @see org.knime.core.node.NodeModel#reset()
     */
    @Override
    protected void reset() {
        if (m_viewName != null) {
            Exception e = execute("DROP VIEW " + m_viewName);
            if (e != null) {
                LOGGER.warn("Unable to delete view: " + m_viewName, e);
            }
            m_viewName = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void loadInternals(final File nodeInternDir,
            final ExecutionMonitor exec) throws IOException {
        NodeSettingsRO sett = NodeSettings.loadFromXML(new FileInputStream(
                new File(nodeInternDir, "internals.xml")));
        m_viewName = sett.getString("view", null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void saveInternals(final File nodeInternDir,
            final ExecutionMonitor exec) throws IOException {
        NodeSettings sett = new NodeSettings("internals");
        sett.addString("view", m_viewName);
        sett.saveToXML(new FileOutputStream(new File(
                nodeInternDir, "internals.xml")));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected DataTableSpec[] configure(final DataTableSpec[] inSpecs)
            throws InvalidSettingsException {
        DBDriverLoader.registerDriver(getDriver());
        return new DataTableSpec[0];
    }

    /**
     * @return user name to login to the database
     */
    protected final String getUser() {
        return m_user;
    }

    /**
     * @return password used to login to the database
     */
    protected final String getPassword() {
        return m_pass;
    }

    /**
     * @return database driver to create connection
     */
    protected final String getDriver() {
        return m_driver;
    }

    /**
     * @return database name to create connection to
     */
    protected final String getDatabaseName() {
        return m_name;
    }

    /**
     * @return SQl query/statement to execute
     */
    protected final String getQuery() {
        return m_query;
    }

}
