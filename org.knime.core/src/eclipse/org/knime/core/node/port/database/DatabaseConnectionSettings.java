/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by
 *  University of Konstanz, Germany and
 *  KNIME GmbH, Konstanz, Germany
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
 * --------------------------------------------------------------------- *
 *
 */
package org.knime.core.node.port.database;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;

import org.knime.core.data.date.DateAndTimeCell;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.KNIMEConstants;
import org.knime.core.node.ModelContent;
import org.knime.core.node.ModelContentRO;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.config.ConfigRO;
import org.knime.core.node.config.ConfigWO;
import org.knime.core.node.util.ConvenienceMethods;
import org.knime.core.node.util.StringHistory;
import org.knime.core.node.workflow.CredentialsProvider;
import org.knime.core.node.workflow.ICredentials;
import org.knime.core.util.KnimeEncryption;
import org.knime.core.util.ThreadUtils;

/**
 *
 * @author Thomas Gabriel, University of Konstanz
 */
public class DatabaseConnectionSettings {

    private static final NodeLogger LOGGER =
        NodeLogger.getLogger(DatabaseConnectionSettings.class);

    /** Config for SQL statement. */
    public static final String CFG_STATEMENT = "statement";

    /** Keeps the history of all loaded driver and its order. */
    public static final StringHistory DRIVER_ORDER = StringHistory.getInstance(
            "database_drivers");

    /** Keeps the history of all database URLs. */
    public static final StringHistory DATABASE_URLS = StringHistory.getInstance(
            "database_urls");

    private static final ExecutorService CONNECTION_CREATOR_EXECUTOR =
            ThreadUtils.executorServiceWithContext(Executors.newCachedThreadPool());

    /**
     * DriverManager login timeout for database connection; not implemented/
     * used by all database drivers.
     */
    private static final int LOGIN_TIMEOUT = initLoginTimeout();
    private static int initLoginTimeout() {
        String tout = System.getProperty(KNIMEConstants.PROPERTY_DATABASE_LOGIN_TIMEOUT);
        int timeout = 15; // default
        if (tout != null) {
            try {
                int t = Integer.parseInt(tout);
                if (t <= 0) {
                    LOGGER.warn("Database login timeout not valid (<=0) '"
                        + tout + "', using default '" + timeout + "'.");
                } else {
                    timeout = t;
                }
            } catch (NumberFormatException nfe) {
                LOGGER.warn("Database login timeout not valid '" + tout
                        + "', using default '" + timeout + "'.");
            }
        }
        LOGGER.debug("Database login timeout: " + timeout + " sec.");
        DriverManager.setLoginTimeout(timeout);
        return timeout;
    }

    /** Used to switch on/off the database connection access (applies only for the same database connection).
     * Default is true, that is all database accesses are synchronized based on single connection; false means off,
     * that is, the access is not synchronized and may lead to database errors.
     */
    private static final boolean SQL_CONCURRENCY = initSQLConcurrency();
    private static boolean initSQLConcurrency() {
        String sconcurrency = System.getProperty(KNIMEConstants.PROPERTY_DATABASE_CONCURRENCY);
        boolean concurrency = true; // default
        if (sconcurrency != null) {
            concurrency = Boolean.parseBoolean(sconcurrency);
        }
        LOGGER.debug("Database concurrency (sync via database connection) is " + concurrency + ".");
        return concurrency;
    }

    /** {@link DriverManager} fetch size to chunk specified number of rows while reading from database. */
    public static final Integer FETCH_SIZE = initFetchSize();
    private static Integer initFetchSize() {
        String fsize = System.getProperty(
                KNIMEConstants.PROPERTY_DATABASE_FETCHSIZE);
        if (fsize != null) {
            try {
                int fetchsize = Integer.parseInt(fsize);
                LOGGER.debug("Database fetch size: " + fetchsize + " rows.");
                return fetchsize;
            } catch (NumberFormatException nfe) {
                LOGGER.warn("Database fetch size not valid '" + fsize
                        + "', no fetch size will be set.");
            }
        }
        return null;
    }

    /** Properties defines the number of rows written in on chunk into the database.
     * @since 2.6 */
    public static final int BATCH_WRITE_SIZE = initBatchWriteSize();
    private static int initBatchWriteSize() {
        String bsize = System.getProperty(KNIMEConstants.PROPERTY_DATABASE_BATCH_WRITE_SIZE);
        if (bsize != null) {
            try {
                final int batchsize = Integer.parseInt(bsize);
                if (batchsize > 0) {
                    LOGGER.debug("Database batch write size: " + batchsize + " rows.");
                    return batchsize;
                } else {
                    LOGGER.warn("Database property knime.database.batch_write_size=" + batchsize
                            + " can't be smaller than 1, using 1 as default.");
                }
            } catch (NumberFormatException nfe) {
                LOGGER.warn("Database batch write size not valid '" + bsize
                        + "', using 1 as default.");
            }
        }
        // default batch write size
        return 1;
    }

    private String m_driver;
    private String m_credName = null;

    private String m_jdbcUrl;
    private String m_user = null;
    private String m_pass = null;

    private String m_timezone = "current"; // use current as of KNIME 2.8, none before 2.8

    private boolean m_validateConnection;

    private boolean m_allowSpacesInColumnNames;

    // this is to fix bug #4066 and not exposed to the user
    private boolean m_rowIdsStartWithZero;

    /**
     * Create a default settings connection object.
     */
    public DatabaseConnectionSettings() {
        // init default driver with the first from the driver list
        // or use Java JDBC-ODBC as default
        String[] history = DRIVER_ORDER.getHistory();
        if (history != null && history.length > 0) {
            m_driver = history[0];
        } else {
            m_driver = DatabaseDriverLoader.JDBC_ODBC_DRIVER;
        }
        // create database name from driver class
        m_jdbcUrl = DatabaseDriverLoader.getURLForDriver(m_driver);
        m_allowSpacesInColumnNames = true;
    }

    /** Create a default database connection object.
     * @param driver the database driver
     * @param jdbcUrl database URL
     * @param user user name
     * @param pass password for user name
     * @param credName credential id from {@link CredentialsProvider} or null
     */
    @Deprecated
    public DatabaseConnectionSettings(final String driver, final String jdbcUrl,
            final String user, final String pass, final String credName) {
        this(driver, jdbcUrl, user, pass, credName, "none");
    }

    /** Create a default database connection object.
     * @param driver the database driver
     * @param jdbcUrl database URL
     * @param user user name
     * @param pass password for user name
     * @param credName credential id from {@link CredentialsProvider} or null
     * @param timezone the TimeZone to correct data/time/timestamp fields
     * @since 2.8
     */
    public DatabaseConnectionSettings(final String driver, final String jdbcUrl,
            final String user, final String pass, final String credName, final String timezone) {
        this();
        m_driver   = driver;
        m_jdbcUrl  = jdbcUrl;
        m_user     = user;
        m_pass     = pass;
        m_credName = credName;
        m_timezone = timezone;
    }

    /**
     * Creates and inits a new database configuration.
     * @param config to load
     * @param cp <code>CredentialProvider</code> used to get user name/password, may be <code>null</code>
     * @throws InvalidSettingsException if settings are invalid
     * @since 2.7
     */
    public DatabaseConnectionSettings(final ConfigRO config, final CredentialsProvider cp)
            throws InvalidSettingsException {
        this();
        loadValidatedConnection(config, cp);
    }

    /**
     * Creates a new <code>DBConnection</code> based on the given connection
     * object.
     * @param conn connection used to copy settings from
     * @throws NullPointerException if the connection is null
     */
    public DatabaseConnectionSettings(final DatabaseConnectionSettings conn) {
        m_driver   = conn.m_driver;
        m_jdbcUrl  = conn.m_jdbcUrl;
        m_user     = conn.m_user;
        m_pass     = conn.m_pass;
        m_credName = conn.m_credName;
        m_timezone = conn.m_timezone;
        m_allowSpacesInColumnNames = conn.m_allowSpacesInColumnNames;
        m_rowIdsStartWithZero = conn.m_rowIdsStartWithZero;
    }

    /** Map the keeps database connection based on the user and URL. */
    private static final Map<ConnectionKey, Connection> CONNECTION_MAP =
        Collections.synchronizedMap(new HashMap<ConnectionKey, Connection>());
    /** Holding the database connection keys used to sync the open connection
     * process. */
    private static final Map<ConnectionKey, ConnectionKey>
        CONNECTION_KEYS = new HashMap<ConnectionKey, ConnectionKey>();

    private static final class ConnectionKey {
        private final String m_un;
        private final String m_pw;
        private final String m_dn;
        private ConnectionKey(final String userName, final String password,
                final String databaseName) {
            m_un = userName;
            m_pw = password;
            m_dn = databaseName;
        }
        /** {@inheritDoc} */
        @Override
        public boolean equals(final Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null || !(obj instanceof ConnectionKey)) {
                return false;
            }
            ConnectionKey ck = (ConnectionKey) obj;
            if (!ConvenienceMethods.areEqual(this.m_un, ck.m_un)
                  || !ConvenienceMethods.areEqual(this.m_pw, ck.m_pw)
                  || !ConvenienceMethods.areEqual(this.m_dn, ck.m_dn)) {
                return false;
            }
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return m_un.hashCode() ^ m_dn.hashCode();
        }
    }

    /** Create a database connection based on this settings. Note, don't close
     * the connection since it cached for subsequent calls or later reuse to
     * same database URL (under the same user name).
     * @return a new database connection object
     * @throws SQLException {@link SQLException}
     * @throws InvalidSettingsException {@link InvalidSettingsException}
     * @throws IllegalBlockSizeException {@link IllegalBlockSizeException}
     * @throws BadPaddingException {@link BadPaddingException}
     * @throws InvalidKeyException {@link InvalidKeyException}
     * @throws IOException {@link IOException}
     * @deprecated use {@link #createConnection(CredentialsProvider)}
     */
    @Deprecated
    public Connection createConnection()
            throws InvalidSettingsException, SQLException,
            BadPaddingException, IllegalBlockSizeException,
            InvalidKeyException, IOException {
        return createConnection(null);
    }

    /** Create a database connection based on this settings. Note, don't close
     * the connection since it cached for subsequent calls or later reuse to
     * same database URL (under the same user name).
     * @return a new database connection object
     * @param cp {@link CredentialsProvider} provides user/password pairs
     * @throws SQLException {@link SQLException}
     * @throws InvalidSettingsException {@link InvalidSettingsException}
     * @throws IllegalBlockSizeException {@link IllegalBlockSizeException}
     * @throws BadPaddingException {@link BadPaddingException}
     * @throws InvalidKeyException {@link InvalidKeyException}
     * @throws IOException {@link IOException}
     */
    public Connection createConnection(final CredentialsProvider cp)
            throws InvalidSettingsException, SQLException,
            BadPaddingException, IllegalBlockSizeException,
            InvalidKeyException, IOException {
        if (m_jdbcUrl == null || m_user == null || m_pass == null || m_driver == null || m_timezone == null) {
            throw new InvalidSettingsException("No settings available to create database connection.");
        }
        final Driver d = DatabaseDriverLoader.registerDriver(m_driver);
        if (!d.acceptsURL(m_jdbcUrl)) {
            throw new InvalidSettingsException("Driver \"" + d + "\" does not accept URL: " + m_jdbcUrl);
        }

        final String dbName = m_jdbcUrl;
        final String user;
        final String pass;
        if (cp == null || m_credName == null) {
            user = m_user;
            pass = m_pass;
        } else {
            ICredentials cred = cp.get(m_credName);
            user = cred.getLogin();
            pass = cred.getPassword();
        }

        // database connection key with user, password and database URL
        ConnectionKey databaseConnKey = new ConnectionKey(user, pass, dbName);

        // retrieve original key and/or modify connection key map
        synchronized (CONNECTION_KEYS) {
            if (CONNECTION_KEYS.containsKey(databaseConnKey)) {
                databaseConnKey = CONNECTION_KEYS.get(databaseConnKey);
            } else {
                CONNECTION_KEYS.put(databaseConnKey, databaseConnKey);
            }
        }

        // sync database connection key: unique with database url and user name
        synchronized (databaseConnKey) {
            Connection conn = CONNECTION_MAP.get(databaseConnKey);
            // if connection already exists
            if (conn != null) {
                try {
                    // and is valid
                    boolean isValid = true;
                    try {
                        isValid = conn.isValid(1);
                    } catch (final Error e) {
                        LOGGER.debug("java.sql.Connection#isValid(1) throws error: " + e.getMessage());
                    }
                    // and is closed
                    if (conn.isClosed() || !isValid) {
                        CONNECTION_MAP.remove(databaseConnKey);
                    } else {
                        conn.clearWarnings();
                        return conn;
                    }
                } catch (Exception e) { // remove invalid connection
                    CONNECTION_MAP.remove(databaseConnKey);
                }
            }
            // if a connection is not available
            Callable<Connection> callable = new Callable<Connection>() {
                /** {@inheritDoc} */
                @Override
                public Connection call() throws Exception {
                    LOGGER.debug("Opening database connection to \"" + dbName + "\"...");
                    return DriverManager.getConnection(dbName, user, pass);
                }
            };
            Future<Connection> task = CONNECTION_CREATOR_EXECUTOR.submit(callable);
            try {
                conn = task.get(LOGIN_TIMEOUT + 1, TimeUnit.SECONDS);
                CONNECTION_MAP.put(databaseConnKey, conn);
                return conn;
            } catch (ExecutionException ee) {
                throw new SQLException(ee.getCause());
            } catch (Throwable t) {
                throw new SQLException(t);
            }
        }
    }

    /**
     * Used to sync access to all databases depending if <code>SQL_CONCURRENCY</code> is true.
     * @param conn connection used to sync access to all databases
     * @return sync object which is either the given connection or an new object (no sync necessary)
     */
    final Object syncConnection(final Connection conn) {
        if (SQL_CONCURRENCY && conn != null) {
            return conn;
        } else {
            return new Object();
        }
    }

    /**
     * Save settings.
     * @param settings connection settings
     */
    public void saveConnection(final ConfigWO settings) {
        settings.addString("driver", m_driver);
        settings.addString("database", m_jdbcUrl);
        if (m_credName == null) {
            settings.addString("user", m_user);
            try {
                if (m_pass == null) {
                    settings.addString("password", null);
                } else {
                    settings.addString("password", KnimeEncryption.encrypt(
                            m_pass.toCharArray()));
                }
            } catch (Throwable t) {
                LOGGER.error("Could not encrypt password, reason: "
                        + t.getMessage(), t);
            }
        } else {
            settings.addString("credential_name", m_credName);
        }
        DRIVER_ORDER.add(m_driver);
        DATABASE_URLS.add(m_jdbcUrl);
        settings.addString("timezone", m_timezone);
        settings.addBoolean("validateConnection", m_validateConnection);
        settings.addBoolean("allowSpacesInColumnNames", m_allowSpacesInColumnNames);
        settings.addBoolean("rowIdsStartWithZero", m_rowIdsStartWithZero);
    }

    /**
     * Validate settings.
     * @param settings to validate
     * @param cp <code>CredentialProvider</code> used to get user name/password
     * @throws InvalidSettingsException if the settings are not valid
     */
    public void validateConnection(final ConfigRO settings,
            final CredentialsProvider cp)
            throws InvalidSettingsException {
        loadConnection(settings, false, cp);
    }

    /**
     * Load validated settings.
     * @param settings to load
     * @param cp <code>CredentialProvider</code> used to get user name/password, may be <code>null</code>
     * @return true, if settings have changed
     * @throws InvalidSettingsException if settings are invalid
     */
    public boolean loadValidatedConnection(final ConfigRO settings,
            final CredentialsProvider cp)
            throws InvalidSettingsException {
        return loadConnection(settings, true, cp);
    }

    private boolean loadConnection(final ConfigRO settings,
            final boolean write, final CredentialsProvider cp)
            throws InvalidSettingsException {
        if (settings == null) {
            throw new InvalidSettingsException("Connection settings not available!");
        }
        String driver = settings.getString("driver");
        String database = settings.getString("database");

        String user = "";
        String password = null;
        String credName = null;
        String timezone = settings.getString("timezone", "none");
        boolean validateConnection = settings.getBoolean("validateConnection", false);
        boolean allowSpacesInColumnNames = settings.getBoolean("allowSpacesInColumnNames", false);
        boolean rowIdsStartWithZero = settings.getBoolean("rowIdsStartWithZero", false);

        boolean useCredential = settings.containsKey("credential_name");
        if (useCredential) {
            credName = settings.getString("credential_name");
            if (cp != null) {
                ICredentials cred = cp.get(credName);
                user = cred.getLogin();
                password = cred.getPassword();
                if (password == null) {
                    LOGGER.warn("Credentials/Password has not been set, using empty password.");
                }
            }
        } else {
            // user and password
            user = settings.getString("user");
            try {
                String pw = settings.getString("password", "");
                if (pw != null) {
                    password = KnimeEncryption.decrypt(pw);
                }
            } catch (Exception e) {
                LOGGER.error("Password could not be decrypted, reason: " + e.getMessage());
            }
        }
        // write settings or skip it
        if (write) {
            m_driver = driver;
            DRIVER_ORDER.add(m_driver);
            boolean changed = false;
            if (useCredential) {
                changed = (m_credName != null) && (credName != null) && credName.equals(m_credName);
                m_credName = credName;
            } else {
                if ((m_user != null) && (m_jdbcUrl != null) && (m_pass != null)) {
                    if (!m_user.equals(user) || !m_jdbcUrl.equals(database) || !m_pass.equals(password)) {
                        changed = true;
                    }
                }
                m_credName = null;
            }
            m_user = user;
            m_pass = (password == null ? "" : password);
            m_jdbcUrl = database;
            m_timezone = timezone;
            m_validateConnection = validateConnection;
            m_allowSpacesInColumnNames = allowSpacesInColumnNames;
            m_rowIdsStartWithZero = rowIdsStartWithZero;
            DATABASE_URLS.add(m_jdbcUrl);
            return changed;
        }
        return false;
    }

    /**
     * Execute statement on current database connection.
     * @param statement to be executed
     * @param cp {@link CredentialsProvider} providing user/password
     * @throws SQLException {@link SQLException}
     * @throws InvalidSettingsException {@link InvalidSettingsException}
     * @throws IllegalBlockSizeException {@link IllegalBlockSizeException}
     * @throws BadPaddingException {@link BadPaddingException}
     * @throws InvalidKeyException {@link InvalidKeyException}
     * @throws IOException {@link IOException}
     */
    public void execute(final String statement, final CredentialsProvider cp)
                throws InvalidKeyException,
            BadPaddingException, IllegalBlockSizeException,
            InvalidSettingsException,
            SQLException, IOException {
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = createConnection(cp);
            stmt = conn.createStatement();
            LOGGER.debug("Executing SQL statement \"" + statement + "\"");
            stmt.execute(statement);
        } finally {
            if (stmt != null) {
                stmt.close();
                stmt = null;
            }
            if (conn != null) {
                conn = null;
            }
        }
    }

    private static final Set<Class<? extends Connection>> AUTOCOMMIT_EXCEPTIONS =
        new HashSet<Class<? extends Connection>>();
    /**
     * Calls {@link java.sql.Connection#setAutoCommit(boolean)} on the connection given the commit flag and catches
     * all <code>Exception</code>s, which is reported only once.
     * @param conn the Connection to call auto commit on.
     * @param commit the commit flag.
     */
    static synchronized void setAutoCommit(final Connection conn, final boolean commit) {
        try {
            conn.setAutoCommit(commit);
        } catch (Exception e) {
            if (!AUTOCOMMIT_EXCEPTIONS.contains(conn.getClass())) {
                AUTOCOMMIT_EXCEPTIONS.add(conn.getClass());
                LOGGER.debug(conn.getClass() + "#setAutoCommit(" + commit + ") error, reason: ", e);
            }
        }
    }

    /**
     * Create connection model with all settings used to create a database
     * connection.
     * @return database connection model
     */
    public ModelContentRO createConnectionModel() {
        ModelContent cont = new ModelContent("database_connection_model");
        saveConnection(cont);
        return cont;
    }

    /**
     * @return database driver used to open the connection
     */
    public final String getDriver() {
        return m_driver;
    }

    /**
     * @return database name used to access the database URL
     * @deprecated use {@link #getJDBCUrl()} instead
     */
    @Deprecated
    public final String getDBName() {
        return m_jdbcUrl;
    }

    /**
     * Returns the JDBC URL for the database.
     *
     * @return a JDBC URL
     * @since 2.10
     */
    public final String getJDBCUrl() {
        return m_jdbcUrl;
    }

    /**
     * @param cp {@link CredentialsProvider}
     * @return user name used to login to the database
     */
    public final String getUserName(final CredentialsProvider cp) {
        if (cp == null || m_credName == null) {
            return m_user;
        } else {
            ICredentials cred = cp.get(m_credName);
            return cred.getLogin();
        }
    }

    /**
     * @param cp {@link CredentialsProvider}
     * @return password (decrypted) used to login to the database
     */
    public final String getPassword(final CredentialsProvider cp) {
        if (cp == null || m_credName == null) {
            return m_pass;
        } else {
            ICredentials cred = cp.get(m_credName);
            return cred.getPassword();
        }
    }

    /** @return the TimeZone.
     * @since 2.8
     */
    public final TimeZone getTimeZone() {
        if (m_timezone.equals("none")) {
            return DateAndTimeCell.UTC_TIMEZONE;
        } else if (m_timezone.equals("current")) {
            return TimeZone.getDefault();
        } else {
            return TimeZone.getTimeZone(m_timezone);
        }
    }

    /** @return the TimeZone correction, offset in milli seconds.
     * @param date in the current date to compute the offset for
     * @since 2.8
     */
    public final long getTimeZoneOffset(final long date) {
        return getTimeZone().getOffset(date);
    }

    /**
     * @return user name used to login to the database
     * @deprecated use {@link #getUserName(CredentialsProvider)}
     */
    @Deprecated
    public final String getUserName() {
        return getUserName(null);
    }

    /**
     * @return password (decrypted) used to login to the database
     * @deprecated use {@link #getPassword(CredentialsProvider)}
     */
    @Deprecated
    public final String getPassword() {
        return getPassword(null);
    }


    /**
     * Set a new database driver.
     * @param driver used to open the connection
     */
    public final void setDriver(final String driver) {
        m_driver = driver;
    }

    /**
     * Set a new database name.
     * @param databaseName used to access the database URL
     * @deprecated use {@link #setJDBCUrl(String)} instead
     */
    @Deprecated
    public final void setDBName(final String databaseName) {
        m_jdbcUrl = databaseName;
    }

    /**
     * Sets the JDBC URL for the database.
     *
     * @param url a JDBC URL
     * @since 2.10
     */
    public final void setJDBCUrl(final String url) {
        m_jdbcUrl = url;
    }


    /**
     * Set a new user name.
     * @param userName used to login to the database
     */
    public final void setUserName(final String userName) {
        m_user = userName;
    }

    /**
     * Set a new password.
     * @param password (decrypted) used to login to the database
     */
    public final void setPassword(final String password) {
        m_pass = password;
    }

    /**
     * Returns the name of the credential entry that should be used. If <code>null</code> is returned username and
     * password should be used instead.
     *
     * @return a credential identifier or <code>null</code>
     * @since 2.10
     */
    public final String getCredentialName() {
        return m_credName;
    }

    /**
     * Returns the name of the credential entry that should be used. If it is set to <code>null</code> username and
     * password should be used instead.
     *
     * @param name a credential identifier or <code>null</code>
     * @since 2.10
     */
    public final void setCredentialName(final String name) {
        m_credName = name;
    }


    /**
     * Returns the manually set timezone that should be assumed for dates returned by the database. If the timezone is
     * set to <tt>current</tt> the client's local timezone should be used. If the timezone is set to <tt>none</tt> no
     * correction is applied
     *
     * @return a timezone identifier, <tt>current/tt>, or <tt>none</tt>
     * @since 2.10
     */
    public final String getTimezone() {
        return m_timezone;
    }

    /**
     * Sets the manually set timezone that should be assumed for dates returned by the database. If the timezone is
     * set to <tt>current</tt> the client's local timezone should be used. If the timezone is set to <tt>none</tt> no
     * correction is applied
     *
     * @param tz a timezone identifier, <tt>current/tt>, or <tt>none</tt>
     * @since 2.10
     */
    public void setTimezone(final String tz) {
        m_timezone = tz;
    }


    /**
     * Returns whether the connection should be validated by dialogs.
     *
     * @return <code>true</code> if the connection should be validated, <code>false</code> otherwise
     * @since 2.10
     */
    public final boolean getValidateConnection() {
        return m_validateConnection;
    }

    /**
     * Sets whether the connection should be validated by dialogs.
     *
     * @param b <code>true</code> if the connection should be validated, <code>false</code> otherwise
     * @since 2.10
     */
    public final void setValidateConnection(final boolean b) {
        m_validateConnection = b;
    }


    /**
     * Returns whether spaces in columns names are allowed and passed on to the database. If spaces are not allowed
     * they will be replaced.
     *
     * @return <code>true</code> if spaces are allowed, <code>false</code> otherwise
     * @since 2.10
     */
    public boolean getAllowSpacesInColumnNames() {
        return m_allowSpacesInColumnNames;
    }


    /**
     * Sets whether spaces in columns names are allowed and passed on to the database. If spaces are not allowed
     * they will be replaced.
     *
     * @param allow <code>true</code> if spaces are allowed, <code>false</code> otherwise
     * @since 2.10
     */
    public void setAllowSpacesInColumnNames(final boolean allow) {
        m_allowSpacesInColumnNames = allow;
    }


    /**
     * Returns whether row IDs returned by a database reader should start with zero (correct behavior) or with
     * (backward compatibility with pre 2.10). The default is <code>false</code>.
     *
     * @return <code>true</code> if row ids should start with 0, <code>false</code> if they should start with 1
     * @since 2.10
     */
    public boolean getRowIdsStartWithZero() {
        return m_rowIdsStartWithZero;
    }


    /**
     * Sets whether row IDs returned by a database reader should start with zero (correct behavior) or with
     * (backward compatibility with pre 2.10). This should only be enabled for nodes that did not exist before 2.10.
     *
     * @param b <code>true</code> if row ids should start with 0, <code>false</code> if they should start with 1
     * @since 2.10
     */
    public void setRowIdsStartWithZero(final boolean b) {
        m_rowIdsStartWithZero = b;
    }


    /**
     * Returns a utility implementation for the current database.
     *
     * @return a statement manipulator
     * @since 2.10
     */
    public DatabaseUtility getUtility() {
        String[] parts = m_jdbcUrl.split(":");
        if ((parts.length < 2) || !"jdbc".equals(parts[0])) {
            throw new IllegalArgumentException("Invalid JDBC URL in settings: " + m_jdbcUrl);
        }
        return  DatabaseUtility.getUtility(parts[1]);
    }
}
